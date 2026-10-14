"""Provider 抽象接口：所有模型接入方必须实现该接口。"""

from abc import ABC, abstractmethod
from collections.abc import AsyncIterator


class BaseProvider(ABC):
    """模型提供商统一接口。"""

    name: str = "base"

    @abstractmethod
    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        """非流式生成，超时由调用方传入，失败必须抛异常。"""

    async def stream_chat(
        self,
        prompt: str,
        model: str | None = None,
        timeout: float = 30.0,
        first_token_timeout: float = 10.0,
    ) -> AsyncIterator[str]:
        """流式生成：逐片段（token/字）产出。

        默认实现：退回非流式 ``chat`` 后一次性产出，保证**所有 Provider 都可用**；
        支持 SSE 的 Provider 应覆盖本方法以获得真实流式效果（首字延迟更低）。

        Args:
            prompt: 用户提示词。
            model: 模型名，缺省由实现决定。
            timeout: 整体超时（秒）。
            first_token_timeout: 首字超时（秒）；默认实现下等价于整体超时上限。
        """
        yield await self.chat(prompt, model=model, timeout=timeout)
