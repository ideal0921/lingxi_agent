"""入库管道与行构造单测（离线运行，不建立 Milvus 连接）。

覆盖：行构造字段映射与向量数量校验、管道编排（解析→切分→向量化→存储）、
状态回写钩子、空文档仍执行替换（清空历史切片）。
"""

from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest

from app.rag.index.mapping import physical_name
from app.rag.index.rows import PAGE_ABSENT, chunk_rows
from app.rag.ingest.embedder import HashEmbedder
from app.rag.ingest.pipeline import IngestEvent, handle_ingest_event, ingest_document
from app.rag.models import Chunk, DocType


def _chunk(chunk_id: str = "c1", page: int | None = None) -> Chunk:
    return Chunk(
        chunk_id=chunk_id,
        doc_id="d1",
        kb_id="kb1",
        tenant_id="t1",
        parent_id="p1",
        content="内容",
        seq=1,
        heading_path="章",
        doc_type=DocType.MARKDOWN,
        page=page,
        meta={"sheet": "S1"},
    )


class TestRowBuilder:
    def test_chunk_rows_field_mapping(self) -> None:
        rows = chunk_rows([_chunk()], [[0.1, 0.2]], [{1: 0.5}])
        row = rows[0]
        assert row["chunk_id"] == "c1"
        assert row["page"] == PAGE_ABSENT  # 无页码 → 占位值
        assert row["doc_type"] == "markdown"  # StrEnum 序列化为字符串
        assert row["meta"] == {"sheet": "S1"}
        assert row["sparse_vector"] == {1: 0.5}

    def test_chunk_rows_rejects_vector_mismatch(self) -> None:
        # 向量数量与分析产物不一致必须拒绝写入，避免向量错位
        with pytest.raises(ValueError, match="数量不一致"):
            chunk_rows([_chunk()], [[0.1]], [])

    def test_parent_rows_field_mapping(self) -> None:
        """父块不单独落库：内容由子块按 parent_id 聚合回填（此处验证 parent_id 已写入子块行）。"""
        rows = chunk_rows([_chunk()], [[0.1, 0.2]], [{1: 0.5}])
        assert rows[0]["parent_id"] == "p1"
        assert rows[0]["seq"] == 1

    def test_physical_name(self) -> None:
        assert physical_name("lingxi_kb_chunk", "v1") == "lingxi_kb_chunk_v1"


class _FakeStore:
    """记录调用参数的假存储，用于离线验证管道编排（不依赖 Milvus）。"""

    def __init__(self) -> None:
        self.replaced: dict | None = None

    def replace_document(
        self,
        *,
        doc_id: str,
        tenant_id: str,
        chunks: list[Chunk],
        dense: list[list[float]],
        sparse: list[dict[int, float]],
    ) -> SimpleNamespace:
        self.replaced = {
            "doc_id": doc_id,
            "tenant_id": tenant_id,
            "chunks": chunks,
            "dense": dense,
            "sparse": sparse,
        }
        return SimpleNamespace(child_count=len(chunks), deleted_child_count=0)


def _write_doc(tmp_path: Path, text: str, name: str = "policy.md") -> Path:
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


class TestPipelineOrchestration:
    def test_ingest_document(self, tmp_path: Path) -> None:
        # 文本需超过子块目标 token（512），才会切出多个子块
        path = _write_doc(tmp_path, "# 售后政策\n\n" + "这是正文内容。" * 200)
        store = _FakeStore()

        result = ingest_document(
            path, doc_id="d1", tenant_id="t1", kb_id="kb1",
            embedder=HashEmbedder(dim=16), store=store,
        )

        assert result.doc_type is DocType.MARKDOWN
        assert result.child_count > 1
        assert result.child_count == len(store.replaced["chunks"])
        assert len(store.replaced["dense"]) == result.child_count
        assert all(len(vector) == 16 for vector in store.replaced["dense"])

    def test_ingest_carries_tenant_and_kb(self, tmp_path: Path) -> None:
        path = _write_doc(tmp_path, "一行内容。")
        store = _FakeStore()

        ingest_document(
            path, doc_id="d2", tenant_id="tenant-a", kb_id="kb-9",
            embedder=HashEmbedder(dim=8), store=store,
        )

        # 租户与知识库需透传至切分产物，用于多租户隔离与过滤
        assert all(chunk.tenant_id == "tenant-a" for chunk in store.replaced["chunks"])
        assert all(chunk.kb_id == "kb-9" for chunk in store.replaced["chunks"])

    def test_empty_document_still_replaces(self, tmp_path: Path) -> None:
        path = _write_doc(tmp_path, "", name="empty.txt")
        store = _FakeStore()

        result = ingest_document(
            path, doc_id="d3", tenant_id="t1", kb_id="kb1",
            embedder=HashEmbedder(dim=8), store=store,
        )

        # 空文档仍需执行替换，以清空该文档的历史切片
        assert result.child_count == 0
        assert store.replaced is not None

    def test_handle_event_invokes_status_callback(self, tmp_path: Path) -> None:
        path = _write_doc(tmp_path, "内容。")
        store = _FakeStore()
        indexed = []

        handle_ingest_event(
            IngestEvent(doc_id="d4", tenant_id="t1", kb_id="kb1", file_path=str(path)),
            embedder=HashEmbedder(dim=8),
            store=store,
            on_indexed=indexed.append,
        )

        # 状态回写钩子应被触发（生产为投递 kb.doc.indexed 事件）
        assert [item.doc_id for item in indexed] == ["d4"]


class TestEmbedder:
    def test_hash_embedder_is_deterministic(self) -> None:
        embedder = HashEmbedder(dim=32)
        first = embedder.embed(["售后政策"])
        second = embedder.embed(["售后政策"])
        assert first.dense == second.dense
        assert first.sparse == second.sparse

    def test_hash_embedder_similar_text_is_closer(self) -> None:
        """相似文本的向量余弦相似度应高于无关文本（验证哈希袋可用性）。"""
        embedder = HashEmbedder(dim=256)
        result = embedder.embed(["退换货时效为48小时", "退换货时效为48小时以内", "完全无关的天气话题"])
        similar = _cosine(result.dense[0], result.dense[1])
        different = _cosine(result.dense[0], result.dense[2])
        assert similar > different

    def test_hash_embedder_empty_text(self) -> None:
        result = HashEmbedder(dim=8).embed([""])
        assert result.dense == [[0.0] * 8]
        assert result.sparse == [{}]


def _cosine(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right, strict=True))
