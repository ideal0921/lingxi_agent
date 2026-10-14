"""Milvus 写入器：子块按 ``doc_id`` 全量替换（先删后插），并支持父块聚合回填。

替换语义：同一文档的旧切片先整体删除再写入新切片，避免新旧版本混杂
（《架构设计》7.4.7）。写入后 ``flush`` 保证立即可见。
父块不单独存储，由 :meth:`ChunkStore.fetch_parent_content` 按 ``parent_id``
聚合同组子块拼接得到。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from pymilvus import MilvusClient

from app.rag.index.mapping import ensure_collection, physical_name
from app.rag.index.rows import chunk_rows
from app.rag.models import Chunk

# 单次 insert 行数上限（避免超大请求体）
_INSERT_BATCH = 500

# 检索输出字段（与 Milvus schema 一致；向量字段不回传，节省带宽）
_SEARCH_OUTPUT_FIELDS = [
    "chunk_id",
    "doc_id",
    "kb_id",
    "parent_id",
    "content",
    "seq",
    "heading_path",
    "doc_type",
    "page",
]


@dataclass(slots=True)
class ReplaceResult:
    """文档替换结果：写入与删除计数，供状态回写与审计。"""

    doc_id: str
    child_count: int
    deleted_child_count: int


def _quote(value: str) -> str:
    """转义 Milvus 过滤表达式中的字符串字面量（防注入/语法破坏）。"""
    return value.replace("\\", "\\\\").replace('"', '\\"')


def _normalize_hit(hit: Mapping) -> dict:
    """把 Milvus 返回项归一化为扁平字段字典。

    兼容两种返回形态：标量字段直接平铺，或包裹在 ``entity`` 子对象内；得分统一
    规整到 ``score`` 键（Milvus 用 ``distance`` 承载相似度/距离）。
    """
    entity = hit.get("entity")
    merged: dict = dict(entity) if isinstance(entity, Mapping) else {}
    for key, value in hit.items():
        if key != "entity":
            merged.setdefault(key, value)
    # hybrid_search 结果的向量主键可能落在 ``id`` 键上
    merged.setdefault("chunk_id", hit.get("id"))
    merged["score"] = float(hit.get("distance", hit.get("score", 0.0)) or 0.0)
    return merged


class ChunkStore:
    """Milvus 子块存储（父子块同集合，父块按 ``parent_id`` 聚合回填）。"""

    def __init__(
        self,
        uri: str,
        *,
        chunk_collection: str,
        dim: int,
        version: str = "v1",
        client: MilvusClient | None = None,
    ) -> None:
        self._client = client or MilvusClient(uri=uri)
        self._logical = chunk_collection
        self._physical = physical_name(chunk_collection, version)
        self._dim = dim

    @property
    def client(self) -> MilvusClient:
        return self._client

    def ensure_collections(self) -> None:
        """幂等创建集合并建立别名（写入与检索统一使用逻辑名）。"""
        ensure_collection(self._client, self._logical, self._physical, dim=self._dim)

    def replace_document(
        self,
        doc_id: str,
        tenant_id: str,
        chunks: Sequence[Chunk],
        dense: Sequence[Sequence[float]],
        sparse: Sequence[Mapping[int, float]],
    ) -> ReplaceResult:
        """按 ``doc_id`` 全量替换文档切片（先删旧片，再写新片，最后 flush）。"""
        self.ensure_collections()
        # Milvus 启用 Partition Key 后，删除表达式必须携带分区键过滤
        condition = f'doc_id == "{_quote(doc_id)}" and tenant_id == "{_quote(tenant_id)}"'
        deleted = self._client.delete(collection_name=self._logical, filter=condition)

        if chunks:
            rows = chunk_rows(chunks, dense, sparse)
            for start in range(0, len(rows), _INSERT_BATCH):
                self._client.insert(
                    collection_name=self._logical, data=rows[start : start + _INSERT_BATCH]
                )
        # flush 保证写入立即可查（Milvus 默认异步可见）
        self._client.flush(self._logical)

        return ReplaceResult(
            doc_id=doc_id,
            child_count=len(chunks),
            deleted_child_count=int(deleted.get("delete_count", 0)),
        )

    def drop_document(self, doc_id: str, tenant_id: str) -> ReplaceResult:
        """下线文档：删除其全部切片（文档元数据与状态由 Java 域负责）。"""
        self.ensure_collections()
        condition = f'doc_id == "{_quote(doc_id)}" and tenant_id == "{_quote(tenant_id)}"'
        deleted = self._client.delete(collection_name=self._logical, filter=condition)
        self._client.flush(self._logical)
        return ReplaceResult(
            doc_id=doc_id,
            child_count=0,
            deleted_child_count=int(deleted.get("delete_count", 0)),
        )

    def count(self, tenant_id: str) -> int:
        """统计指定租户的子块数量（用于验证入库结果）。"""
        result = self._client.query(
            collection_name=self._logical,
            filter=f'tenant_id == "{_quote(tenant_id)}"',
            output_fields=["count(*)"],
        )
        return int(result[0]["count(*)"])

    def search_chunks(
        self,
        *,
        dense: Sequence[float],
        sparse: Mapping[int, float] | None,
        tenant_id: str,
        kb_id: str | None = None,
        limit: int = 20,
    ) -> list[dict]:
        """稠密 + 稀疏混合检索（RRFRanker 融合），返回归一化字段字典列表。

        **强制携带 ``tenant_id`` 过滤**（Partition Key，多租户隔离）；``kb_id``
        为可选二级过滤。稀疏向量为空（如仅云端稠密通道）时退化为纯稠密检索，
        保证链路仍可用。

        Args:
            dense: 查询稠密向量。
            sparse: 查询稀疏词权重（``index → weight``）；空则仅走稠密检索。
            tenant_id: 租户标识（必填）。
            kb_id: 知识库标识（可选）。
            limit: 召回条数上限。

        Returns:
            候选列表，每项为扁平字段字典（含 ``score`` 相似度）。
        """
        self.ensure_collections()
        condition = f'tenant_id == "{_quote(tenant_id)}"'
        if kb_id:
            condition = f'{condition} and kb_id == "{_quote(kb_id)}"'

        if sparse:
            # 多向量字段检索必须用 hybrid_search 并显式指定 anns_field；
            # 过滤条件须写入各子请求的 expr（hybrid_search 的顶层 filter 不生效）
            from pymilvus import AnnSearchRequest, RRFRanker

            requests = [
                AnnSearchRequest(
                    data=[list(dense)],
                    anns_field="dense_vector",
                    param={"metric_type": "COSINE"},
                    limit=limit,
                    expr=condition,
                ),
                AnnSearchRequest(
                    data=[dict(sparse)],
                    anns_field="sparse_vector",
                    param={"metric_type": "IP"},
                    limit=limit,
                    expr=condition,
                ),
            ]
            raw = self._client.hybrid_search(
                collection_name=self._logical,
                reqs=requests,
                ranker=RRFRanker(),
                limit=limit,
                output_fields=_SEARCH_OUTPUT_FIELDS,
            )
            hits = raw[0] if raw else []
        else:
            raw = self._client.search(
                collection_name=self._logical,
                data=[list(dense)],
                anns_field="dense_vector",
                search_params={"metric_type": "COSINE"},
                limit=limit,
                filter=condition,
                output_fields=_SEARCH_OUTPUT_FIELDS,
            )
            hits = raw[0] if raw else []

        return [_normalize_hit(hit) for hit in hits]

    def fetch_parent_content(self, parent_id: str, tenant_id: str) -> str:
        """按 ``parent_id`` 聚合同组子块并按 ``seq`` 拼接，得到父块内容（检索回填）。

        返回空串表示父块不存在（可能已被替换删除）。
        """
        rows = self._client.query(
            collection_name=self._logical,
            filter=f'tenant_id == "{_quote(tenant_id)}" and parent_id == "{_quote(parent_id)}"',
            output_fields=["content", "seq"],
        )
        ordered = sorted(rows, key=lambda row: row["seq"])
        return "\n".join(row["content"] for row in ordered)

    def close(self) -> None:
        """释放客户端连接。"""
        self._client.close()
