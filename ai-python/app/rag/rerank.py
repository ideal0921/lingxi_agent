"""精排（Rerank）：对混合检索召回候选做二次重排，提升送入模型的知识质量。

管道位置（《架构设计》7.4.3）：Milvus 混合检索召回 Top-K（默认 10）→ **本模块精排 Top5** →
父块回填 → Context 拼装 → LLM。精排模型为 bge-reranker-v2-m3。

实现（经 :func:`create_reranker` 按配置装配）：
- :class:`CloudReranker`（**生产默认**）：调用云端 Rerank REST（兼容 SiliconFlow /
  Jina / Cohere 的通用契约 ``{query, documents, top_n}`` → ``results[].{index,
  relevance_score}``），免本机部署；带超时、指数退避重试与返回值校验。
- :class:`IdentityReranker`：保持输入顺序的降级实现（沿用 RRF 融合名次），用于
  密钥缺失、云端不可用或离线单测。

降级策略（架构强制项）：精排不可用时**不得阻断检索**——调用方捕获异常后退回
RRF 融合顺序截断 TopK（见 :meth:`app.rag.retrieve.Retriever._rerank`）。
"""

from __future__ import annotations

import logging
import time
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Protocol

from app.config import Settings

logger = logging.getLogger(__name__)


class RerankerDependencyError(RuntimeError):
    """精排依赖或凭证缺失、云端调用失败。"""


@dataclass(slots=True)
class RerankScore:
    """单条精排结果。

    Attributes:
        index: 候选在**输入序列**中的原始下标（用于回填原候选的元数据）。
        score: 相关性得分（越大越相关，模型无关的归一化约定）。
    """

    index: int
    score: float


class Reranker(Protocol):
    """精排统一协议：把候选文本按与查询的相关性降序重排。"""

    def rerank(self, query: str, documents: Sequence[str], top_n: int) -> list[RerankScore]:
        """返回按相关性降序的前 ``top_n`` 条精排结果。"""
        ...


class IdentityReranker:
    """降级实现：不改变顺序，仅按 ``top_n`` 截断（沿用 RRF 融合名次）。"""

    def rerank(self, _query: str, documents: Sequence[str], top_n: int) -> list[RerankScore]:
        # score 置 0：表示本次未产生真实精排分，排序依据仍是上游 RRF 名次
        return [RerankScore(index=index, score=0.0) for index in range(min(top_n, len(documents)))]


class CloudReranker:
    """云端精排客户端（bge-reranker-v2-m3）。

    请求契约（三家云商通用，见 .env.example 说明）::

        POST {base_url}/rerank
        { "model": "BAAI/bge-reranker-v2-m3", "query": "...",
          "documents": [...], "top_n": N, "return_documents": false }
        → { "results": [ { "index": 3, "relevance_score": 0.87 }, ... ] }

    调用约束（对齐架构强制项）：超时控制、指数退避重试、返回下标范围校验。
    """

    def __init__(
        self,
        api_key: str,
        *,
        base_url: str = "https://api.siliconflow.cn/v1",
        model: str = "BAAI/bge-reranker-v2-m3",
        timeout: float = 10.0,
        max_retries: int = 1,
        transport: Any = None,
    ) -> None:
        if not api_key:
            raise RerankerDependencyError(
                "使用云端精排需配置环境变量 LINGXI_RERANK_API_KEY"
            )
        self._api_key = api_key
        self._endpoint = f"{base_url.rstrip('/')}/rerank"
        self._model = model
        self._timeout = timeout
        self._max_retries = max(0, max_retries)
        self._transport = transport  # 仅测试注入（httpx.MockTransport），生产为 None
        # 长连接客户端：复用 TCP/TLS 连接，避免每次调用重做握手（首字延迟优化）
        self._client: Any = None

    def _get_client(self) -> Any:
        """惰性创建并复用 httpx.Client（进程内长连接）。"""
        if self._client is None:
            import httpx

            self._client = httpx.Client(timeout=self._timeout, transport=self._transport)
        return self._client

    def close(self) -> None:
        """释放长连接（进程停机或测试清理时调用）。"""
        if self._client is not None:
            self._client.close()
            self._client = None

    def rerank(self, query: str, documents: Sequence[str], top_n: int) -> list[RerankScore]:
        items = list(documents)
        if not items or top_n <= 0:
            return []
        scores = self._request(query, items)
        # 云端一般已按分数降序返回，此处仍显式排序，屏蔽不同厂商的实现差异
        ordered = sorted(scores, key=lambda score: score.score, reverse=True)
        return ordered[:top_n]

    def _request(self, query: str, documents: list[str]) -> list[RerankScore]:
        """单次请求（候选为召回 Top-K，量级小，无需分批）：重试 + 解析 + 校验（复用长连接）。"""
        payload = {
            "model": self._model,
            "query": query,
            "documents": documents,
            "top_n": len(documents),  # 取回全部候选分数，截断由上层按 top_n 统一处理
            "return_documents": False,
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        last_error: Exception | None = None
        for attempt in range(self._max_retries + 1):
            if attempt > 0:
                time.sleep(2**attempt * 0.2)  # 指数退避 0.4s / 0.8s ...
            try:
                response = self._get_client().post(self._endpoint, json=payload, headers=headers)
                response.raise_for_status()
                data = response.json()
                return self._parse(data, len(documents))
            except Exception as exc:  # noqa: BLE001 - 重试后仍失败才抛出，交上层降级
                last_error = exc
                logger.warning("云端精排请求失败(第%d次): %s", attempt + 1, exc)
        raise RerankerDependencyError(f"云端精排调用失败: {last_error}") from last_error

    def _parse(self, data: dict, candidate_size: int) -> list[RerankScore]:
        """解析响应并校验下标范围，防止越界导致回填错位。"""
        raw_results = data.get("results") or data.get("data") or []
        scores: list[RerankScore] = []
        for item in raw_results:
            index = int(item["index"])
            if not 0 <= index < candidate_size:
                raise RerankerDependencyError(
                    f"云端精排返回下标越界：{index}，候选数 {candidate_size}"
                )
            score = float(item.get("relevance_score", item.get("score", 0.0)))
            scores.append(RerankScore(index=index, score=score))
        if not scores:
            raise RerankerDependencyError("云端精排返回结果为空")
        return scores


def create_reranker(config: Settings) -> Reranker:
    """按配置装配 Reranker。

    Args:
        config: 应用配置（读取 ``rerank_provider`` 等字段）。

    Returns:
        对应实现。``cloud`` 缺少密钥时降级为 :class:`IdentityReranker`
        （记录告警，不抛错，保证检索链路可用）。

    Raises:
        ValueError: 未知 provider。
    """
    provider = config.rerank_provider
    if provider in ("none", "identity"):
        return IdentityReranker()
    if provider == "cloud":
        if not config.rerank_api_key:
            logger.warning("未配置 LINGXI_RERANK_API_KEY，精排降级为 RRF 融合顺序")
            return IdentityReranker()
        return CloudReranker(
            config.rerank_api_key,
            base_url=config.rerank_base_url,
            model=config.rerank_model,
            timeout=config.rerank_timeout,
            max_retries=config.rerank_max_retries,
        )
    raise ValueError(f"未知的 rerank provider: {provider}")
