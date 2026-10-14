"""LLM 网关降级链、装配工厂与故障演练单测（纯内存，不触网）。"""

from collections.abc import AsyncIterator

import pytest

from app.config import Settings
from app.gateway.providers import DeepSeekProvider, GlmProvider, KimiProvider, QwenProvider
from app.gateway.providers.base import BaseProvider
from app.gateway.router import LLMGateway, create_gateway


class BoomProvider(BaseProvider):
    """总是失败的 Provider，模拟主模型故障。"""

    name = "boom"

    def __init__(self, name: str = "boom") -> None:
        self.name = name

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        raise TimeoutError("mock timeout")


class OkProvider(BaseProvider):
    """正常 Provider，模拟备用模型（默认 stream_chat 退回非流式，可流式产出）。"""

    name = "ok"

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        return "ok-answer"


class StreamBoomProvider(BaseProvider):
    """流式调用总是失败的 Provider（首个片段前抛错）。"""

    name = "stream-boom"

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        raise TimeoutError("mock timeout")

    async def stream_chat(
        self,
        prompt: str,
        model: str | None = None,
        timeout: float = 30.0,
        first_token_timeout: float = 10.0,
    ) -> AsyncIterator[str]:
        # 空循环使函数成为异步生成器；迭代时先抛错，验证降级到下一 Provider
        for _ in ():
            yield ""

        raise TimeoutError("mock stream timeout")


async def test_gateway_degrades_to_backup() -> None:
    """主模型失败后应自动降级到备用模型并返回结果。"""
    gateway = LLMGateway([BoomProvider(), OkProvider()], max_retries=1, backoff_base=0.01)
    assert await gateway.generate("hi") == "ok-answer"


async def test_gateway_all_failed_raises() -> None:
    """全部 Provider 失败时抛异常。"""
    gateway = LLMGateway([BoomProvider()], max_retries=0, backoff_base=0.01)
    with pytest.raises(RuntimeError, match="全部模型调用失败"):
        await gateway.generate("hi")


class TestFaultDrill:
    """故障演练：主模型与多级备用依次失效时，仍应由末位可用模型兜底。"""

    async def test_degrades_through_multiple_backups(self) -> None:
        gateway = LLMGateway(
            [BoomProvider("deepseek"), BoomProvider("qwen"), OkProvider()],
            max_retries=0,
            backoff_base=0.01,
        )
        assert await gateway.generate("hi") == "ok-answer"

    async def test_stream_degrades_through_multiple_backups(self) -> None:
        gateway = LLMGateway(
            [StreamBoomProvider(), StreamBoomProvider(), OkProvider()],
            max_retries=0,
            backoff_base=0.01,
        )
        assert [chunk async for chunk in gateway.stream("hi")] == ["ok-answer"]

    async def test_retry_round_after_whole_chain_failed(self) -> None:
        """整条链失败后，进入下一轮重试（指数退避后再次尝试）。"""
        gateway = LLMGateway([BoomProvider(), BoomProvider("boom2")], max_retries=1)
        with pytest.raises(RuntimeError, match="全部模型调用失败"):
            await gateway.generate("hi")


def test_backoff_delay_is_exponential() -> None:
    """指数退避：等待时间应随重试轮次翻倍（旧实现 base**n 会递减，已修正）。"""
    gateway = LLMGateway([], backoff_base=0.5)
    assert [gateway._backoff_delay(n) for n in (1, 2, 3)] == [0.5, 1.0, 2.0]


def _config(**overrides) -> Settings:
    """构造隔离的配置：显式覆盖四个 Provider 密钥，避免受本地 .env 影响。"""
    base = {
        "deepseek_api_key": "",
        "qwen_api_key": "",
        "glm_api_key": "",
        "kimi_api_key": "",
    }
    base.update(overrides)
    return Settings(**base)


class TestProviderChainFactory:
    def test_only_configured_providers_included(self) -> None:
        """未配置密钥的 Provider 不纳入降级链（避免无谓的首字超时等待）。"""
        gateway = create_gateway(_config(deepseek_api_key="d", qwen_api_key="q"))
        assert [provider.name for provider in gateway.providers] == ["deepseek", "qwen"]

    def test_chain_order_follows_config(self) -> None:
        gateway = create_gateway(
            _config(llm_provider_chain="kimi,deepseek", kimi_api_key="k", deepseek_api_key="d")
        )
        assert [provider.name for provider in gateway.providers] == ["kimi", "deepseek"]

    def test_unknown_provider_skipped(self) -> None:
        gateway = create_gateway(
            _config(llm_provider_chain="deepseek,foo", deepseek_api_key="d")
        )
        assert [provider.name for provider in gateway.providers] == ["deepseek"]

    def test_blank_entries_skipped(self) -> None:
        gateway = create_gateway(_config(llm_provider_chain=" deepseek, ,qwen ", deepseek_api_key="d", qwen_api_key="q"))
        assert [provider.name for provider in gateway.providers] == ["deepseek", "qwen"]

    def test_empty_chain_when_no_keys(self) -> None:
        gateway = create_gateway(_config(deepseek_api_key="", qwen_api_key="", glm_api_key="", kimi_api_key=""))
        assert gateway.providers == ()

    def test_retry_params_come_from_config(self) -> None:
        gateway = create_gateway(_config(deepseek_api_key="d", llm_max_retries=5, llm_retry_backoff_base=0.25))
        assert gateway._backoff_delay(3) == 1.0  # 0.25 * 2^2


class TestProviderConfigMapping:
    """各 Provider 应读取自身配置块（base_url / model 不串用）。"""

    def test_qwen_reads_own_config(self) -> None:
        provider = QwenProvider(_config(qwen_api_key="q", qwen_base_url="https://example.com/v1", qwen_model="qwen-max"))
        assert provider.name == "qwen"
        assert provider.base_url == "https://example.com/v1"
        assert provider.default_model == "qwen-max"
        assert provider.configured is True

    def test_glm_reads_own_config(self) -> None:
        provider = GlmProvider(_config(glm_api_key="g"))
        assert provider.name == "glm"
        assert provider.base_url == "https://open.bigmodel.cn/api/paas/v4"

    def test_kimi_reads_own_config(self) -> None:
        provider = KimiProvider(_config(kimi_api_key="k"))
        assert provider.name == "kimi"
        assert provider.base_url == "https://api.moonshot.cn/v1"

    def test_deepseek_reads_own_config(self) -> None:
        provider = DeepSeekProvider(_config(deepseek_api_key="d"))
        assert provider.name == "deepseek"
        assert provider.base_url == "https://api.deepseek.com"

    def test_unconfigured_provider_not_configured(self) -> None:
        assert QwenProvider(_config()).configured is False
