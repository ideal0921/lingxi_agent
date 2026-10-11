"""Provider 抽象接口：所有模型接入方必须实现该接口。"""

from abc import ABC, abstractmethod


class BaseProvider(ABC):
    """模型提供商统一接口。"""

    name: str = "base"

    @abstractmethod
    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        """非流式生成，超时由调用方传入，失败必须抛异常。"""
