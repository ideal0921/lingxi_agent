"""Agent 运行时（M3-3.7 / 3.8）：LangGraph 图编排 + 对话状态 + 流式生成。

- :mod:`app.agent.state`：对话状态、消息结构与流式事件类型；
- :mod:`app.agent.graph`：最小图（加载上下文 → 检索 → 生成）、流式生成与生产装配。
"""

from app.agent.graph import build_graph, build_prompt, create_agent, format_context, stream_answer
from app.agent.state import AgentState, ChatMessage, StreamEvent, StreamKind

__all__ = [
    "AgentState",
    "ChatMessage",
    "StreamEvent",
    "StreamKind",
    "build_graph",
    "build_prompt",
    "create_agent",
    "format_context",
    "stream_answer",
]
