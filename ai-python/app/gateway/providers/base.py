"""Provider 抽象接口：所有模型接入方必须实现该接口。"""

from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass


@dataclass(slots=True)
class ToolCallRequest:
    """模型请求调用一个工具（``arguments`` 为 JSON 字符串，未解析）。"""

    id: str
    name: str
    arguments: str


@dataclass(slots=True)
class ProviderStreamEvent:
    """Provider 流式事件：文本增量 或 一个已累积完成的工具调用。

    两者互斥地出现在同一事件里：``delta`` 非空表示文本片段；``tool_call`` 非空表示
    模型已决定调用某工具（此时增量分片已按 index 拼装完整）。既无 ``delta`` 也无
    ``tool_call`` 的事件是流终止信号。
    """

    delta: str = ""
    tool_call: ToolCallRequest | None = None


def render_messages(messages: list[dict]) -> str:
    """将 OpenAI 格式消息降级拼装为纯文本提示词（供不支持 tools 的 Provider 兜底）。

    仅取 ``role`` 与 ``content``，忽略 ``tool_calls`` / ``tool_call_id`` 等结构化字段，
    保证「无原生 tools 能力」的 Provider 仍能基于对话内容产出回答。
    """
    lines: list[str] = []
    for message in messages:
        role = message.get("role", "user")
        content = message.get("content") or ""
        lines.append(f"{role}: {content}")
    return "\n".join(lines)


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

    async def stream_events(
        self,
        messages: list[dict],
        tools: list[dict] | None = None,
        model: str | None = None,
    ) -> AsyncIterator[ProviderStreamEvent]:
        """以 OpenAI 消息格式流式生成；``tools`` 非空时解析 ``tool_calls`` 增量。

        默认实现：不支持原生 tools 的 Provider 退回文本流——忽略 ``tools``，把
        ``messages`` 各条内容拼成提示词后复用 :meth:`stream_chat`，逐片段产出
        ``delta`` 事件，末尾补一个空事件作为终止信号。具备 SSE tool_calls 能力的
        Provider 应覆盖本方法以其原生协议产出 ``tool_call`` 事件。

        Args:
            messages: OpenAI 格式消息列表（role/content/tool_calls/tool_call_id）。
            tools: OpenAI 工具定义列表；为空表示纯文本对话。
            model: 模型名，缺省由实现决定。
        """
        prompt = render_messages(messages)
        async for delta in self.stream_chat(prompt, model=model):
            yield ProviderStreamEvent(delta=delta)
        yield ProviderStreamEvent()
