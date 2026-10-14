"""检索管道（同步在线）：混合检索 → 精排 → 父块回填。

管道（《架构设计》7.4.3）::

    Query → Milvus Hybrid Search(稠密 kNN + 稀疏匹配, RRFRanker 融合) → Top-K（默认 10）
          → bge-reranker-v2-m3 精排 Top5 → 按 parent_id 回填父块 → Context 拼装 → LLM

本模块负责 ①两路召回融合 ②精排截断 ③父块回填 三步；Query 改写/扩展与 Context
拼装分别归 Agent 运行时（M3-3.7）与其下游，不在本模块展开。

依赖注入：``store``（实现 :meth:`app.rag.index.writer.ChunkStore.search_chunks` /
``fetch_parent_content``）与 ``embedder``、``reranker`` 均由调用方传入，模块本身
不绑定 Milvus SDK 与具体模型，便于离线单测与替换实现。

降级策略（架构强制项）：精排不可用（密钥缺失 / 调用失败）时退回 RRF 融合顺序
截断 TopK，**不阻断检索**。
"""

from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Protocol

from app.config import Settings
from app.rag.ingest.embedder import Embedder
from app.rag.rerank import IdentityReranker, Reranker, create_reranker

logger = logging.getLogger(__name__)


class ChunkSearchStore(Protocol):
    """检索所需的最小存储能力（便于以假存储替换 ChunkStore 做离线单测）。"""

    def search_chunks(
        self,
        *,
        dense: Sequence[float],
        sparse: Mapping[int, float] | None,
        tenant_id: str,
        kb_id: str | None = None,
        limit: int = 20,
    ) -> list[dict]:
        """混合检索召回候选。"""
        ...

    def fetch_parent_content(self, parent_id: str, tenant_id: str) -> str:
        """按 ``parent_id`` 聚合子块得到父块内容。"""
        ...


@dataclass(slots=True)
class RetrievalHit:
    """检索命中项：子块元数据 + 得分 + 回填的父块内容。

    Attributes:
        content: 命中的子块原文（用于精排与引用定位）。
        parent_content: 按 ``parent_id`` 聚合的父块内容，供 LLM 生成时补足上下文。
        score: 得分；精排成功时为精排相关性分，降级时为 RRF 融合分。
    """

    chunk_id: str
    doc_id: str
    kb_id: str
    parent_id: str
    content: str
    seq: int
    heading_path: str
    doc_type: str
    page: int | None
    score: float
    parent_content: str = ""


class Retriever:
    """检索编排器：召回 Top-K（默认 10）→ 精排 Top5 → 父块回填。"""

    def __init__(
        self,
        store: ChunkSearchStore,
        embedder: Embedder,
        *,
        reranker: Reranker | None = None,
        candidate_k: int = 20,
        top_k: int = 5,
    ) -> None:
        """
        Args:
            store: 检索存储（生产为 :class:`ChunkStore`）。
            embedder: 查询向量化器（与入库侧同一实现，保证向量空间一致）。
            reranker: 精排器；``None`` 时按 RRF 顺序截断（等价降级）。
            candidate_k: 混合检索召回条数（默认 20）。
            top_k: 精排后返回条数（默认 5）。
        """
        self._store = store
        self._embedder = embedder
        self._reranker = reranker
        self._candidate_k = max(1, candidate_k)
        self._top_k = max(1, top_k)

    def retrieve(
        self,
        query: str,
        *,
        tenant_id: str,
        kb_id: str | None = None,
        top_k: int | None = None,
    ) -> list[RetrievalHit]:
        """检索指定租户知识库，返回精排并回填父块的 TopK 命中。

        Args:
            query: 用户查询原文。
            tenant_id: 租户标识（**强制过滤**，隔离多租户知识）。
            kb_id: 知识库标识（可选二级过滤）。
            top_k: 覆盖默认返回条数（默认 :attr:`_top_k`）。

        Returns:
            命中列表；查询为空或无召回时返回空列表。
        """
        if not query or not query.strip():
            return []
        embedded = self._embedder.embed([query])
        if not embedded.dense and not embedded.sparse:
            return []

        candidates = self._recall(embedded, tenant_id, kb_id)
        ranked = self._rerank(query, candidates, top_k or self._top_k)
        return self._fill_parent(ranked, tenant_id)

    def _recall(self, embedded, tenant_id: str, kb_id: str | None) -> list[RetrievalHit]:
        """调用混合检索并把行字典映射为命中项。"""
        dense = embedded.dense[0] if embedded.dense else []
        sparse = embedded.sparse[0] if embedded.sparse else None
        rows = self._store.search_chunks(
            dense=dense,
            sparse=sparse,
            tenant_id=tenant_id,
            kb_id=kb_id,
            limit=self._candidate_k,
        )
        return [self._to_hit(row) for row in rows]

    @staticmethod
    def _to_hit(row: Mapping) -> RetrievalHit:
        """行字典 → 命中项；无页码（占位 -1 / 缺失）归一为 ``None``。"""
        page = row.get("page")
        return RetrievalHit(
            chunk_id=str(row.get("chunk_id", "")),
            doc_id=str(row.get("doc_id", "")),
            kb_id=str(row.get("kb_id", "")),
            parent_id=str(row.get("parent_id", "")),
            content=str(row.get("content", "")),
            seq=int(row.get("seq", 0) or 0),
            heading_path=str(row.get("heading_path", "")),
            doc_type=str(row.get("doc_type", "")),
            page=None if page is None or int(page) < 0 else int(page),
            score=float(row.get("score", 0.0) or 0.0),
        )

    def _rerank(
        self, query: str, candidates: list[RetrievalHit], top_k: int
    ) -> list[RetrievalHit]:
        """精排并按 ``top_k`` 截断；精排异常时降级为 RRF 顺序，不阻断检索。"""
        if not candidates:
            return []
        limit = min(top_k, len(candidates))
        if self._reranker is None or isinstance(self._reranker, IdentityReranker):
            return candidates[:limit]
        try:
            scores = self._reranker.rerank(
                query, [hit.content for hit in candidates], limit
            )
        except Exception as exc:  # noqa: BLE001 - 精排失败必须降级而非抛错
            logger.warning("精排失败，降级为 RRF 融合顺序: %s", exc)
            return candidates[:limit]

        ranked: list[RetrievalHit] = []
        for item in scores:
            hit = candidates[item.index]
            hit.score = item.score  # 用精排分覆盖 RRF 分，便于引用标注与阈值过滤
            ranked.append(hit)
        return ranked

    def _fill_parent(self, hits: list[RetrievalHit], tenant_id: str) -> list[RetrievalHit]:
        """按 ``parent_id`` 回填父块内容；同一父块只查一次（命中去重缓存）。"""
        cache: dict[str, str] = {}
        for hit in hits:
            if not hit.parent_id:
                continue
            if hit.parent_id not in cache:
                cache[hit.parent_id] = (
                    self._store.fetch_parent_content(hit.parent_id, tenant_id) or ""
                )
            hit.parent_content = cache[hit.parent_id]
        return hits


def create_retriever(store: ChunkSearchStore, config: Settings) -> Retriever:
    """按配置装配检索器（生产入口：向量化器 + 精排器 + 召回/截断参数）。

    查询向量走 :class:`CachedEmbedder` 缓存包装：客服场景重复问题命中率高，
    命中即省去云端稠密嵌入的网络往返（首字延迟的主要来源，M3-3.8 优化）。

    Args:
        store: 检索存储（生产为 :class:`ChunkStore`）。
        config: 应用配置。

    Returns:
        配置好的 :class:`Retriever`；精排密钥缺失时自动降级。
    """
    from app.rag.ingest.embedder import CachedEmbedder, create_embedder

    embedder = CachedEmbedder(
        create_embedder(config),
        max_size=config.embed_cache_size,
        ttl=config.embed_cache_ttl,
    )
    return Retriever(
        store,
        embedder,
        reranker=create_reranker(config),
        candidate_k=config.retrieve_candidate_k,
        top_k=config.retrieve_top_k,
    )
