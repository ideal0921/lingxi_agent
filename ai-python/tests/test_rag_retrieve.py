"""检索编排单测（M3-3.6，离线运行，不连接 Milvus）。

覆盖：召回参数与过滤条件透传、精排重排、精排失败降级、父块回填去重缓存、
空查询短路、页码归一化，以及 Recall@5 行为断言（相关候选能否进入 Top5）。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence

from app.config import Settings
from app.rag.ingest.embedder import HashEmbedder
from app.rag.rerank import RerankScore
from app.rag.retrieve import Retriever, create_retriever

_DIM = 16


def _row(index: int, parent_id: str = "p1", page: int = -1) -> dict:
    return {
        "chunk_id": f"c{index}",
        "doc_id": "d1",
        "kb_id": "kb1",
        "parent_id": parent_id,
        "content": f"候选文本{index}",
        "seq": index,
        "heading_path": "章 > 节",
        "doc_type": "markdown",
        "page": page,
        "score": 1.0 - index * 0.01,
    }


class _FakeStore:
    """记录调用参数的假存储，按预设顺序返回候选。"""

    def __init__(self, rows: list[dict]) -> None:
        self._rows = rows
        self.search_calls: list[dict] = []
        self.parent_calls: list[str] = []

    def search_chunks(
        self,
        *,
        dense: Sequence[float],
        sparse: Mapping[int, float] | None,
        tenant_id: str,
        kb_id: str | None = None,
        limit: int = 20,
    ) -> list[dict]:
        self.search_calls.append(
            {"dense": list(dense), "sparse": sparse, "tenant_id": tenant_id,
             "kb_id": kb_id, "limit": limit}
        )
        return self._rows

    def fetch_parent_content(self, parent_id: str, _tenant_id: str) -> str:
        self.parent_calls.append(parent_id)
        return f"父块内容:{parent_id}"


class _FixedReranker:
    """按预设下标顺序返回精排结果，用于验证重排与截断。

    分数按返回位置递减（首位 1.0），与上游 RRF 分数取值区间不同，便于验证
    ``RetrievalHit.score`` 确实被精排分覆盖。
    """

    def __init__(self, order: list[int]) -> None:
        self._order = order

    def rerank(self, _query: str, _documents: Sequence[str], top_n: int) -> list[RerankScore]:
        order = self._order[:top_n]
        return [
            RerankScore(index=index, score=1.0 - position * 0.1)
            for position, index in enumerate(order)
        ]


class _BoomReranker:
    """始终抛错的精排器，用于验证降级。"""

    def rerank(self, _query: str, _documents: Sequence[str], _top_n: int) -> list[RerankScore]:
        raise RuntimeError("精排服务不可用")


def _retriever(store, *, reranker=None, candidate_k: int = 20, top_k: int = 5) -> Retriever:
    return Retriever(
        store,
        HashEmbedder(dim=_DIM),
        reranker=reranker,
        candidate_k=candidate_k,
        top_k=top_k,
    )


class TestRetrieverRecall:
    def test_passes_filter_and_recall_params(self) -> None:
        store = _FakeStore([_row(0)])
        retriever = _retriever(store, candidate_k=20)

        retriever.retrieve("退换货时效", tenant_id="t-1", kb_id="kb-9")

        call = store.search_calls[0]
        assert call["tenant_id"] == "t-1"  # 强制租户过滤
        assert call["kb_id"] == "kb-9"
        assert call["limit"] == 20  # 自定义召回数原样透传
        assert call["dense"] and call["sparse"]  # 双路向量均参与

    def test_empty_query_returns_empty_without_search(self) -> None:
        store = _FakeStore([_row(0)])
        retriever = _retriever(store)

        assert retriever.retrieve("   ", tenant_id="t-1") == []
        assert store.search_calls == []

    def test_page_absent_normalized_to_none(self) -> None:
        store = _FakeStore([_row(0, page=-1)])
        retriever = _retriever(store)

        hits = retriever.retrieve("查询", tenant_id="t-1")

        assert hits[0].page is None  # Milvus 占位 -1 → None


class TestRerankApplication:
    def test_rerank_reorders_and_truncates(self) -> None:
        store = _FakeStore([_row(i) for i in range(6)])
        # 精排把原第 3 位提到首位，且只取 Top5
        retriever = _retriever(store, reranker=_FixedReranker([3, 0, 1, 2, 4, 5]), top_k=5)

        hits = retriever.retrieve("查询", tenant_id="t-1")

        assert [hit.chunk_id for hit in hits] == ["c3", "c0", "c1", "c2", "c4"]
        assert hits[0].score == 1.0  # 精排分覆盖 RRF 分

    def test_recall_at_5_requires_rerank_for_deep_candidate(self) -> None:
        """相关候选排在 RRF 第 8 位：无精排时被 Top5 截断（Recall@5=0），有精排则命中。"""
        store = _FakeStore([_row(i) for i in range(10)])
        relevant = "c7"

        degraded = _retriever(store).retrieve("查询", tenant_id="t-1")  # 默认降级顺序
        assert relevant not in [hit.chunk_id for hit in degraded]

        ranked = _retriever(store, reranker=_FixedReranker([7, 0, 1, 2, 3])).retrieve(
            "查询", tenant_id="t-1"
        )
        assert relevant in [hit.chunk_id for hit in ranked]  # 精排后进入 Top5

    def test_rerank_failure_degrades_to_rrf_order(self) -> None:
        store = _FakeStore([_row(i) for i in range(6)])
        retriever = _retriever(store, reranker=_BoomReranker(), top_k=3)

        hits = retriever.retrieve("查询", tenant_id="t-1")

        # 精排抛错不应阻断检索，退回 RRF 顺序前 3 条
        assert [hit.chunk_id for hit in hits] == ["c0", "c1", "c2"]


class TestParentBackfill:
    def test_fills_parent_and_dedups_queries(self) -> None:
        # 同一父块下的 3 条子块仅需 1 次父块查询（去重缓存）
        store = _FakeStore([_row(0, "p1"), _row(1, "p1"), _row(2, "p2")])
        retriever = _retriever(store)

        hits = retriever.retrieve("查询", tenant_id="t-1")

        assert hits[0].parent_content == "父块内容:p1"
        assert hits[2].parent_content == "父块内容:p2"
        assert store.parent_calls == ["p1", "p2"]

    def test_empty_parent_id_is_skipped(self) -> None:
        store = _FakeStore([_row(0, "")])
        retriever = _retriever(store)

        hits = retriever.retrieve("查询", tenant_id="t-1")

        assert hits[0].parent_content == ""
        assert store.parent_calls == []


class TestCreateRetriever:
    def test_wires_config_params(self) -> None:
        store = _FakeStore([_row(0)])
        config = Settings(
            embedder_provider="hash",
            embed_dim=_DIM,
            rerank_provider="none",
            retrieve_candidate_k=30,
            retrieve_top_k=3,
        )

        retriever = create_retriever(store, config)

        assert isinstance(retriever, Retriever)
        # 参数经配置注入：召回 30、截断 3
        retriever.retrieve("查询", tenant_id="t-1")
        assert store.search_calls[0]["limit"] == 30
        assert len(retriever.retrieve("查询", tenant_id="t-1")) == min(3, 1)
