"""Embedding 通道单测（M3-3.4 方案 C：云端稠密 + 本地稀疏）。

全部离线运行：云端通道用 ``httpx.MockTransport`` 拦截，不触网；本地稀疏通道
直接调用 jieba。
"""

from __future__ import annotations

import json

import httpx
import pytest

from app.config import Settings
from app.rag.ingest.embedder import (
    CachedEmbedder,
    CloudDenseEmbedder,
    EmbedderDependencyError,
    EmbedResult,
    HashEmbedder,
    HybridEmbedder,
    KeywordSparseEmbedder,
    create_embedder,
)

_DIM = 16  # 测试用较小维度，加速断言


def _mock_transport(captured: list[dict]) -> httpx.MockTransport:
    """构造 MockTransport：记录请求体并返回符合 OpenAI 兼容格式的稠密向量。"""

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        captured.append(
            {
                "url": str(request.url),
                "auth": request.headers.get("Authorization"),
                "model": payload["model"],
                "inputs": payload["input"],
            }
        )
        return httpx.Response(
            200,
            json={"data": [{"embedding": [0.1] * _DIM} for _ in payload["input"]]},
        )

    return httpx.MockTransport(handler)


class TestCloudDenseEmbedder:
    def test_missing_api_key_raises(self) -> None:
        with pytest.raises(EmbedderDependencyError, match="LINGXI_DENSE_EMBED_API_KEY"):
            CloudDenseEmbedder("")

    def test_request_shape_and_parsing(self) -> None:
        captured: list[dict] = []
        embedder = CloudDenseEmbedder(
            "test-key", model="baai/bge-m3", dim=_DIM, transport=_mock_transport(captured)
        )

        result = embedder.embed(["退换货时效", "物流查询"])

        assert result.dense == [[0.1] * _DIM, [0.1] * _DIM]
        # 云端不返回稀疏词权重 → 空字典占位，由本地稀疏侧补齐
        assert result.sparse == [{}, {}]
        assert captured[0]["model"] == "baai/bge-m3"
        assert captured[0]["auth"] == "Bearer test-key"
        assert captured[0]["inputs"] == ["退换货时效", "物流查询"]
        # 默认指向国内低延迟服务（硅基流动），OpenAI 兼容端点
        assert captured[0]["url"] == "https://api.siliconflow.cn/v1/embeddings"

    def test_batching_splits_requests(self) -> None:
        captured: list[dict] = []
        embedder = CloudDenseEmbedder(
            "k", dim=_DIM, batch_size=2, transport=_mock_transport(captured)
        )

        result = embedder.embed(["a", "b", "c"])

        assert len(result.dense) == 3
        assert [len(item["inputs"]) for item in captured] == [2, 1]

    def test_dimension_mismatch_raises(self) -> None:
        transport = httpx.MockTransport(
            lambda request: httpx.Response(200, json={"data": [{"embedding": [0.0] * 3}]})
        )
        embedder = CloudDenseEmbedder("k", dim=_DIM, max_retries=0, transport=transport)

        with pytest.raises(EmbedderDependencyError, match="嵌入维度不符"):
            embedder.embed(["a"])

    def test_empty_input(self) -> None:
        embedder = CloudDenseEmbedder("k", dim=_DIM, transport=_mock_transport([]))
        assert embedder.embed([]).dense == []


class TestCloudDenseConnectionReuse:
    """长连接复用（首字延迟优化）：同一实例跨请求复用 httpx.Client。"""

    def test_client_is_reused_and_closable(self) -> None:
        embedder = CloudDenseEmbedder("k", dim=_DIM, transport=_mock_transport([]))
        assert embedder._client is None, "未调用前不应建连"

        embedder.embed(["a"])
        first_client = embedder._client
        assert first_client is not None

        embedder.embed(["b"])
        assert embedder._client is first_client, "应复用同一客户端（避免重复 TCP/TLS 握手）"

        embedder.close()
        assert embedder._client is None, "close 后应释放连接"


class TestKeywordSparseEmbedder:
    def test_weights_deterministic(self) -> None:
        embedder = KeywordSparseEmbedder()
        first = embedder.embed(["退换货时效为48小时"]).sparse[0]
        second = embedder.embed(["退换货时效为48小时"]).sparse[0]
        assert first, "有效词项应产生非空权重"
        assert first == second, "同一文本的稀疏权重应可复现"

    def test_no_dense_output(self) -> None:
        """稀疏侧不产出稠密向量；误直接传给入库管道会因数量不一致而明确报错。"""
        result = KeywordSparseEmbedder().embed(["文本"])
        assert result.dense == []
        assert len(result.sparse) == 1

    def test_similar_text_shares_more_terms(self) -> None:
        embedder = KeywordSparseEmbedder()
        result = embedder.embed(["退换货时效为48小时", "退换货时效为48小时以内", "天气晴朗适合出游"])
        similar = _jaccard(result.sparse[0], result.sparse[1])
        different = _jaccard(result.sparse[0], result.sparse[2])
        assert similar > different, "相近文本的稀疏词项重合度应高于无关文本"

    def test_empty_text(self) -> None:
        assert KeywordSparseEmbedder().embed([""]).sparse == [{}]


def _jaccard(left: dict[int, float], right: dict[int, float]) -> float:
    left_terms, right_terms = set(left), set(right)
    return len(left_terms & right_terms) / len(left_terms | right_terms) if left_terms else 0.0


class TestHybridEmbedder:
    def test_combines_dense_and_sparse(self) -> None:
        hybrid = HybridEmbedder(
            dense=HashEmbedder(dim=_DIM), sparse=KeywordSparseEmbedder()
        )

        result = hybrid.embed(["退换货时效", "物流查询"])

        assert len(result.dense) == 2
        assert all(len(vector) == _DIM for vector in result.dense)
        assert all(isinstance(weights, dict) for weights in result.sparse)
        assert hybrid.dim == _DIM

    def test_empty_input(self) -> None:
        hybrid = HybridEmbedder(dense=HashEmbedder(dim=_DIM), sparse=KeywordSparseEmbedder())
        assert hybrid.embed([]).dense == []


class TestCreateEmbedder:
    def test_hash_provider(self) -> None:
        embedder = create_embedder(Settings(embedder_provider="hash", embed_dim=_DIM))
        assert isinstance(embedder, HashEmbedder)

    def test_cloud_hybrid_provider(self) -> None:
        embedder = create_embedder(
            Settings(
                embedder_provider="cloud-hybrid",
                embed_dim=_DIM,
                dense_embed_api_key="test-key",
            )
        )
        assert isinstance(embedder, HybridEmbedder)
        assert embedder.dim == _DIM

    def test_cloud_hybrid_without_key_raises(self) -> None:
        config = Settings(embedder_provider="cloud-hybrid", dense_embed_api_key="")
        with pytest.raises(EmbedderDependencyError):
            create_embedder(config)

    def test_unknown_provider_raises(self) -> None:
        with pytest.raises(ValueError, match="未知的 embedder provider"):
            create_embedder(Settings(embedder_provider="unknown"))


class _CountingEmbedder:
    """统计下游调用次数的假嵌入器（验证缓存是否真的省掉了下游调用）。"""

    def __init__(self, dim: int = _DIM) -> None:
        self._dim = dim
        self.calls = 0
        self.embedded_texts: list[list[str]] = []

    @property
    def dim(self) -> int:
        return self._dim

    def embed(self, texts):
        self.calls += 1
        items = list(texts)
        self.embedded_texts.append(items)
        return EmbedResult(
            dense=[[float(len(text))] * self._dim for text in items],
            sparse=[{len(text): 1.0} for text in items],
        )


class TestCachedEmbedder:
    def test_hit_skips_downstream_call(self) -> None:
        inner = _CountingEmbedder()
        cached = CachedEmbedder(inner, max_size=8, ttl=60.0)

        first = cached.embed(["退款多久能到账"])
        second = cached.embed(["退款多久能到账"])

        assert inner.calls == 1, "第二次相同文本不应再调用下游（首字延迟优化核心）"
        assert first.dense == second.dense
        assert first.sparse == second.sparse
        assert (cached.hits, cached.misses) == (1, 1)

    def test_partial_hit_preserves_order(self) -> None:
        inner = _CountingEmbedder()
        cached = CachedEmbedder(inner, max_size=8, ttl=60.0)
        cached.embed(["命中文本"])

        result = cached.embed(["命中文本", "全新未命中的文本", "命中文本"])

        assert len(result.dense) == 3
        # 位置 0 与 2（命中）应与首次结果一致；位置 1 为新计算
        assert result.dense[0] == result.dense[2]
        assert result.dense[1] != result.dense[0]
        # 下沉给下游的只有未命中的一条
        assert inner.embedded_texts[-1] == ["全新未命中的文本"]

    def test_disabled_when_size_zero(self) -> None:
        inner = _CountingEmbedder()
        cached = CachedEmbedder(inner, max_size=0)

        cached.embed(["x"])
        cached.embed(["x"])

        assert inner.calls == 2, "max_size=0 应禁用缓存"

    def test_lru_eviction(self) -> None:
        inner = _CountingEmbedder()
        cached = CachedEmbedder(inner, max_size=2, ttl=60.0)

        cached.embed(["a"])
        cached.embed(["b"])
        cached.embed(["c"])  # 触发淘汰最久未用的 a
        cached.embed(["a"])  # a 已淘汰，需重新计算

        assert inner.calls == 4

    def test_ttl_expiry(self) -> None:
        inner = _CountingEmbedder()
        cached = CachedEmbedder(inner, max_size=8, ttl=0.0)  # 立即过期

        cached.embed(["x"])
        cached.embed(["x"])

        assert inner.calls == 2, "TTL 过期后应重新计算"

    def test_empty_input_passthrough(self) -> None:
        inner = _CountingEmbedder()
        cached = CachedEmbedder(inner, max_size=8)

        assert cached.embed([]).dense == []
        assert inner.calls == 0

    def test_dim_delegates_to_inner(self) -> None:
        assert CachedEmbedder(_CountingEmbedder(dim=8)).dim == 8
