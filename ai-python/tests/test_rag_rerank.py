"""Rerank 精排单测（M3-3.6）。

全部离线运行：云端通道用 ``httpx.MockTransport`` 拦截，不触网。覆盖请求契约、
响应解析与下标校验、重试后仍失败抛错、以及工厂装配与降级策略。
"""

from __future__ import annotations

import json

import httpx
import pytest

from app.config import Settings
from app.rag.rerank import (
    CloudReranker,
    IdentityReranker,
    RerankerDependencyError,
    create_reranker,
)


def _mock_transport(captured: list[dict], results: list[dict]) -> httpx.MockTransport:
    """构造 MockTransport：记录请求体并返回符合 Rerank REST 契约的结果。"""

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        captured.append(
            {
                "url": str(request.url),
                "auth": request.headers.get("Authorization"),
                "model": payload["model"],
                "query": payload["query"],
                "documents": payload["documents"],
                "top_n": payload["top_n"],
            }
        )
        return httpx.Response(200, json={"results": results})

    return httpx.MockTransport(handler)


class TestCloudReranker:
    def test_missing_api_key_raises(self) -> None:
        with pytest.raises(RerankerDependencyError, match="LINGXI_RERANK_API_KEY"):
            CloudReranker("")

    def test_request_shape_and_ordering(self) -> None:
        captured: list[dict] = []
        # 云端故意返回乱序，验证客户端显式排序
        reranker = CloudReranker(
            "test-key",
            model="BAAI/bge-reranker-v2-m3",
            transport=_mock_transport(
                captured,
                [{"index": 0, "relevance_score": 0.2}, {"index": 2, "relevance_score": 0.9}],
            ),
        )

        scores = reranker.rerank("退换货时效", ["a", "b", "c"], top_n=3)

        assert [item.index for item in scores] == [2, 0]  # 按分数降序
        assert scores[0].score == 0.9
        assert captured[0]["url"].endswith("/rerank")
        assert captured[0]["auth"] == "Bearer test-key"
        assert captured[0]["model"] == "BAAI/bge-reranker-v2-m3"
        assert captured[0]["query"] == "退换货时效"
        assert captured[0]["top_n"] == 3  # 取回全部候选，截断由上层处理

    def test_top_n_truncates(self) -> None:
        reranker = CloudReranker(
            "k",
            transport=_mock_transport(
                [],
                [{"index": i, "relevance_score": 1.0 - i * 0.1} for i in range(5)],
            ),
        )

        scores = reranker.rerank("q", ["a", "b", "c", "d", "e"], top_n=2)

        assert len(scores) == 2

    def test_empty_documents_skips_request(self) -> None:
        captured: list[dict] = []
        reranker = CloudReranker("k", transport=_mock_transport(captured, []))

        assert reranker.rerank("q", [], top_n=5) == []
        assert captured == []  # 无候选时不应发起请求

    def test_index_out_of_range_raises(self) -> None:
        transport = httpx.MockTransport(
            lambda request: httpx.Response(
                200, json={"results": [{"index": 9, "relevance_score": 0.5}]}
            )
        )
        reranker = CloudReranker("k", max_retries=0, transport=transport)

        with pytest.raises(RerankerDependencyError, match="下标越界"):
            reranker.rerank("q", ["a", "b"], top_n=2)

    def test_empty_results_raises(self) -> None:
        transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"results": []}))
        reranker = CloudReranker("k", max_retries=0, transport=transport)

        with pytest.raises(RerankerDependencyError, match="结果为空"):
            reranker.rerank("q", ["a"], top_n=1)

    def test_http_error_retries_then_raises(self) -> None:
        attempts = {"count": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            attempts["count"] += 1
            return httpx.Response(500, json={"error": "boom"})

        reranker = CloudReranker("k", max_retries=1, transport=httpx.MockTransport(handler))

        with pytest.raises(RerankerDependencyError, match="云端精排调用失败"):
            reranker.rerank("q", ["a"], top_n=1)
        assert attempts["count"] == 2, "应重试一次（共 2 次请求）"

    def test_recovers_after_transient_failure(self) -> None:
        attempts = {"count": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            attempts["count"] += 1
            if attempts["count"] == 1:
                return httpx.Response(503, json={"error": "temporary"})
            return httpx.Response(200, json={"results": [{"index": 0, "relevance_score": 0.7}]})

        reranker = CloudReranker("k", max_retries=1, transport=httpx.MockTransport(handler))

        scores = reranker.rerank("q", ["a"], top_n=1)

        assert scores[0].index == 0
        assert attempts["count"] == 2


class TestCloudRerankerConnectionReuse:
    """长连接复用（首字延迟优化）：同一实例跨请求复用 httpx.Client。"""

    def test_client_is_reused_and_closable(self) -> None:
        results = [{"index": 0, "relevance_score": 0.9}]
        reranker = CloudReranker("k", transport=_mock_transport([], results))
        assert reranker._client is None, "未调用前不应建连"

        reranker.rerank("q", ["a"], top_n=1)
        first_client = reranker._client
        assert first_client is not None

        reranker.rerank("q", ["b"], top_n=1)
        assert reranker._client is first_client, "应复用同一客户端（避免重复 TCP/TLS 握手）"

        reranker.close()
        assert reranker._client is None, "close 后应释放连接"


class TestIdentityReranker:
    def test_keeps_order_and_truncates(self) -> None:
        scores = IdentityReranker().rerank("q", ["a", "b", "c"], top_n=2)
        assert [item.index for item in scores] == [0, 1]
        assert all(item.score == 0.0 for item in scores)


class TestCreateReranker:
    def test_cloud_provider(self) -> None:
        reranker = create_reranker(
            Settings(rerank_provider="cloud", rerank_api_key="test-key")
        )
        assert isinstance(reranker, CloudReranker)

    def test_cloud_without_key_degrades(self) -> None:
        """密钥缺失时降级为 IdentityReranker，不抛错（保证检索链路可用）。"""
        reranker = create_reranker(Settings(rerank_provider="cloud", rerank_api_key=""))
        assert isinstance(reranker, IdentityReranker)

    def test_none_provider(self) -> None:
        assert isinstance(create_reranker(Settings(rerank_provider="none")), IdentityReranker)

    def test_unknown_provider_raises(self) -> None:
        with pytest.raises(ValueError, match="未知的 rerank provider"):
            create_reranker(Settings(rerank_provider="unknown"))
