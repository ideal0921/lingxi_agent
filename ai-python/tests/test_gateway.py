"""LLM 网关降级与重试单测（纯内存，不触网）。"""

from app.gateway.providers.base import BaseProvider
from app.gateway.router import LLMGateway


class BoomProvider(BaseProvider):
    """总是失败的 Provider，模拟主模型故障。"""

    name = "boom"

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        raise TimeoutError("mock timeout")


class OkProvider(BaseProvider):
    """正常 Provider，模拟备用模型。"""

    name = "ok"

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        return "ok-answer"


async def test_gateway_degrades_to_backup() -> None:
    """主模型失败后应自动降级到备用模型并返回结果。"""
    gateway = LLMGateway([BoomProvider(), OkProvider()], max_retries=1, backoff_base=0.01)
    assert await gateway.generate("hi") == "ok-answer"


async def test_gateway_all_failed_raises() -> None:
    """全部 Provider 失败时抛异常。"""
    gateway = LLMGateway([BoomProvider()], max_retries=0, backoff_base=0.01)
    try:
        await gateway.generate("hi")
    except RuntimeError as exc:
        assert "全部模型调用失败" in str(exc)
    else:
        raise AssertionError("应抛出 RuntimeError")
