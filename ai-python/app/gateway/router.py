"""LLM 网关路由：多模型降级链 + 指数退避重试。

约束（架构强制）：
- 所有 LLM 调用含超时
- 失败按指数退避重试，最多 llm_max_retries 次
- 主模型失败自动降级到备用 Provider
"""

import asyncio
import logging
from collections.abc import Sequence

from app.config import settings
from app.gateway.providers import DeepSeekProvider
from app.gateway.providers.base import BaseProvider

logger = logging.getLogger(__name__)


class LLMGateway:
    """统一 LLM 网关。"""

    def __init__(
        self,
        providers: Sequence[BaseProvider],
        max_retries: int = settings.llm_max_retries,
        backoff_base: float = settings.llm_retry_backoff_base,
        timeout: float = settings.llm_timeout,
    ) -> None:
        # 按顺序组成降级链：前面的为主模型，失败依次降级
        self._providers = list(providers)
        self._max_retries = max_retries
        self._backoff_base = backoff_base
        self._timeout = timeout

    async def generate(self, prompt: str, model: str | None = None) -> str:
        last_error: Exception | None = None
        for attempt in range(self._max_retries + 1):
            if attempt > 0:
                # 指数退避：base^attempt 秒
                await asyncio.sleep(self._backoff_base**attempt)
            for provider in self._providers:
                try:
                    return await provider.chat(prompt, model=model, timeout=self._timeout)
                except Exception as exc:  # noqa: BLE001 - 任一 provider 失败都需降级
                    last_error = exc
                    logger.warning(
                        "LLM provider=%s 调用失败(第%d次): %s", provider.name, attempt + 1, exc
                    )
        raise RuntimeError(f"全部模型调用失败: {last_error}") from last_error


# 网关单例：M3 在此注入 qwen/glm/kimi 等 Provider 形成完整降级链
gateway = LLMGateway([DeepSeekProvider()])
