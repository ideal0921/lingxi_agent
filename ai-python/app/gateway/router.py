"""LLM 网关路由：多模型降级链 + 指数退避重试（非流式 / 流式双通道）。

约束（架构强制）：
- 所有 LLM 调用含超时（首字 10s / 总 120s）
- 失败按指数退避重试，最多 llm_max_retries 次
- 主模型失败自动降级到备用 Provider

流式降级说明：**仅在尚未产出任何片段时**才允许切换 Provider/重试；一旦已经产出
片段，再切换会产生「半截内容重复」，故记录日志后正常结束（已产出的内容不回滚）。
"""

import asyncio
import logging
from collections.abc import AsyncIterator, Sequence
from contextlib import aclosing

from app.config import Settings, settings
from app.gateway.providers import (
    DeepSeekProvider,
    GlmProvider,
    KimiProvider,
    OpenAICompatibleProvider,
    QwenProvider,
)
from app.gateway.providers.base import BaseProvider

logger = logging.getLogger(__name__)

# Provider 名 → 构造器注册表（供 create_gateway 按配置链装配，新增模型在此登记）
_PROVIDER_REGISTRY: dict[str, type[OpenAICompatibleProvider]] = {
    "deepseek": DeepSeekProvider,
    "qwen": QwenProvider,
    "glm": GlmProvider,
    "kimi": KimiProvider,
}


class LLMGateway:
    """统一 LLM 网关。"""

    def __init__(
        self,
        providers: Sequence[BaseProvider],
        max_retries: int = settings.llm_max_retries,
        backoff_base: float = settings.llm_retry_backoff_base,
        timeout: float = settings.llm_timeout,
        first_token_timeout: float = settings.llm_first_token_timeout,
    ) -> None:
        # 按顺序组成降级链：前面的为主模型，失败依次降级
        self._providers = list(providers)
        self._max_retries = max_retries
        self._backoff_base = backoff_base
        self._timeout = timeout
        self._first_token_timeout = first_token_timeout

    @property
    def providers(self) -> tuple[BaseProvider, ...]:
        """降级链（只读，按配置顺序，供观测与单测断言）。"""
        return tuple(self._providers)

    def _backoff_delay(self, attempt: int) -> float:
        """指数退避延迟（秒）：第 ``attempt`` 轮等待 ``base * 2^(attempt-1)``。

        注意：旧实现用 ``base**attempt``（base<1 时反而递减），非指数增长，已修正。
        """
        return self._backoff_base * (2 ** (attempt - 1))

    async def generate(self, prompt: str, model: str | None = None) -> str:
        last_error: Exception | None = None
        for attempt in range(self._max_retries + 1):
            if attempt > 0:
                # 指数退避：base * 2^(attempt-1) 秒
                await asyncio.sleep(self._backoff_delay(attempt))
            for provider in self._providers:
                try:
                    return await provider.chat(prompt, model=model, timeout=self._timeout)
                except Exception as exc:  # noqa: BLE001 - 任一 provider 失败都需降级
                    last_error = exc
                    logger.warning(
                        "LLM provider=%s 调用失败(第%d次): %s", provider.name, attempt + 1, exc
                    )
        raise RuntimeError(f"全部模型调用失败: {last_error}") from last_error

    async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
        """流式生成：按降级链轮询 Provider，逐片段产出。

        首字超时保护：等待**首个片段**时施加 ``first_token_timeout``，超时视为该
        Provider 失败并切换下一个；已产出片段后超时不再降级（避免半截重复）。
        """
        last_error: Exception | None = None
        for attempt in range(self._max_retries + 1):
            if attempt > 0:
                await asyncio.sleep(self._backoff_delay(attempt))
            for provider in self._providers:
                emitted = False
                try:
                    async with aclosing(
                        provider.stream_chat(
                            prompt,
                            model=model,
                            timeout=self._timeout,
                            first_token_timeout=self._first_token_timeout,
                        )
                    ) as stream:
                        while True:
                            try:
                                if emitted:
                                    delta = await anext(stream)
                                else:
                                    async with asyncio.timeout(self._first_token_timeout):
                                        delta = await anext(stream)
                            except StopAsyncIteration:
                                return
                            emitted = True
                            yield delta
                except Exception as exc:  # noqa: BLE001 - 宏观降级判定
                    last_error = exc
                    logger.warning(
                        "LLM 流式 provider=%s 失败(第%d次): %s", provider.name, attempt + 1, exc
                    )
                    if emitted:
                        # 已产出片段，无法安全降级（会重复半截内容），结束本轮流
                        logger.error("流式中断于 provider=%s，已产出内容不回滚", provider.name)
                        return
        raise RuntimeError(f"全部模型流式调用失败: {last_error}") from last_error


def create_gateway(config: Settings = settings) -> LLMGateway:
    """按配置装配降级链：仅纳入**已配置密钥**的 Provider，未配置者跳过并告警。

    跳过未配置者而非保留占位，可避免每次请求都先向必然失败的 Provider 发起一次
    无谓调用（省去首字超时等待）。链为空说明未配置任何密钥，调用将直接失败。
    """
    providers: list[BaseProvider] = []
    for raw_name in config.llm_provider_chain.split(","):
        name = raw_name.strip().lower()
        if not name:
            continue
        builder = _PROVIDER_REGISTRY.get(name)
        if builder is None:
            logger.warning("未知 LLM provider=%s，已跳过（可选值: %s）", name, ", ".join(_PROVIDER_REGISTRY))
            continue
        provider = builder(config)
        if not provider.configured:
            logger.warning("LLM provider=%s 未配置密钥，已跳过（不参与降级链）", name)
            continue
        providers.append(provider)
    if not providers:
        logger.warning("LLM 降级链为空：未配置任何 Provider 密钥，生成调用将直接失败")
    else:
        logger.info("LLM 降级链已装配: %s", " > ".join(provider.name for provider in providers))
    return LLMGateway(
        providers,
        max_retries=config.llm_max_retries,
        backoff_base=config.llm_retry_backoff_base,
        timeout=config.llm_timeout,
        first_token_timeout=config.llm_first_token_timeout,
    )


# 网关单例：由配置装配的完整降级链（DeepSeek 主 → qwen/glm/kimi 备）
gateway = create_gateway(settings)
