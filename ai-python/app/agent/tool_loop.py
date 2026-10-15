"""工具调用循环（M4-4.1）：把「模型 → 工具 → 模型」的 Function Calling 循环接到流式链路上。

设计取舍：

- **流式优先**：每一轮都用流式请求（``gateway.stream_events``）。模型直接作答时首字延迟与
  无工具路径**完全一致**；只有当本轮事件里出现 ``tool_call`` 时才执行工具并进入下一轮。
  代价：若模型同时输出正文与工具调用（罕见），正文已被下发，不做回撤。
- **只产出原始文本增量**：本模块**不负责** PII 还原，只按顺序 yield 各轮文本片段；还原由
  调用方用**单条** ``PiiMasker.restore_stream`` 管道包住整条流完成。若在此处逐片段调用
  ``restore``，占位符被切在相邻片段之间时会漏出半截（还原器需跨片段缓冲）。
- **轮次上限**：``max_rounds`` 内必须收敛，超出则回填「无法继续查询、建议转人工」，避免死循环。
- **结果回填**：按 OpenAI 协议追加 ``assistant(tool_calls)`` 与 ``tool(result)`` 消息，
  使模型能基于真实业务数据作答（而不是凭空编造）。
- **被拒调用也回填**：参数校验失败/未注册工具的内容会作为 ``tool`` 结果回填，
  模型据此自我纠正或改走转人工，形成闭环。
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass
from typing import Any

from app.agent.state import ChatMessage
from app.agent.tools.executor import ToolExecutor
from app.agent.tools.registry import ToolRegistry
from app.agent.tools.spec import ToolContext, ToolInvocation

logger = logging.getLogger(__name__)

# 历史消息角色 → OpenAI 角色（system 由提示词承担，不入历史）
_ROLE_MAP = {"user": "user", "agent": "assistant", "assistant": "assistant"}

TOOL_ROUND_LIMIT_MESSAGE = "抱歉，查询次数已达上限，暂时无法继续为您处理，建议转人工客服核实。"


@dataclass(slots=True)
class _TextOnlyEvent:
    """仅文本的降级事件（Provider 未实现 tools 能力时使用）。"""

    delta: str = ""
    tool_call: Any = None


def build_tool_messages(
    *,
    system_prompt: str,
    knowledge_text: str = "",
    summary: str = "",
    memory: str = "",
    history: Sequence[ChatMessage] | None = None,
    query: str = "",
) -> list[dict[str, Any]]:
    """拼装 OpenAI 消息序列：人设 →（用户长期记忆 + 会话摘要 + 知识片段）→ 历史 → 本轮问题。

    分层记忆（M4-4.4）：**跨会话长期画像在前，会话内摘要其后**；两段均已在调用方脱敏。
    """
    messages: list[dict[str, Any]] = [{"role": "system", "content": system_prompt}]
    context_parts: list[str] = []
    if (memory or "").strip():
        context_parts.append("【用户长期记忆】\n" + memory.strip())
    if (summary or "").strip():
        context_parts.append("【会话摘要】\n" + summary.strip())
    if (knowledge_text or "").strip():
        context_parts.append(knowledge_text.strip())
    if context_parts:
        messages.append({"role": "system", "content": "\n\n".join(context_parts)})
    for message in history or []:
        role = _ROLE_MAP.get(message.role)
        if role and message.content:
            messages.append({"role": role, "content": message.content})
    messages.append({"role": "user", "content": query})
    return messages


def _iter_provider_events(
    gateway: Any, messages: list[dict[str, Any]], tools: list[dict] | None
) -> AsyncIterator[Any]:
    """取 Provider 事件流；网关未实现 tools 能力时降级为「纯文本」事件。"""
    if hasattr(gateway, "stream_events"):
        return gateway.stream_events(messages, tools=tools)

    async def _fallback() -> AsyncIterator[_TextOnlyEvent]:
        prompt = "\n\n".join(
            f"{item['role']}：{item.get('content', '')}" for item in messages if item.get("content")
        )
        async for delta in gateway.stream(prompt):
            yield _TextOnlyEvent(delta=delta)

    return _fallback()


def _to_openai_tool_call(call: Any) -> dict[str, Any]:
    """把 Provider 事件中的工具调用转成 OpenAI 协议字段（用于回填 assistant 消息）。"""
    return {
        "id": getattr(call, "id", "") or "",
        "type": "function",
        "function": {
            "name": getattr(call, "name", "") or "",
            "arguments": getattr(call, "arguments", "") or "{}",
        },
    }


async def stream_tool_answer(
    *,
    gateway: Any,
    messages: list[dict[str, Any]],
    registry: ToolRegistry,
    executor: ToolExecutor,
    ctx: ToolContext,
    max_rounds: int = 3,
) -> AsyncIterator[str]:
    """执行「流式 + Function Calling」循环，按顺序产出各轮的**原始文本增量**。

    产出的是未还原的文本（可能含 PII 占位符）；调用方须用**单条**持久
    ``PiiMasker.restore_stream`` 管道包住本生成器，才能保证占位符跨片段、跨轮次
    均不被切断。

    Args:
        gateway: LLM 网关（需提供 ``stream_events``；缺失时降级为纯文本流）。
        messages: OpenAI 格式消息序列（首轮），循环中会追加工具结果。
        registry: 工具注册表（白名单，同时提供 tools 定义）。
        executor: 工具执行器（校验/超时重试/降级/审计）。
        ctx: 工具执行上下文（租户/会话等由服务端注入）。
        max_rounds: 工具轮次上限（含首轮）。
    """
    tools = registry.openai_tools()
    rounds = 0

    while True:
        rounds += 1
        tool_calls: list[Any] = []
        async for event in _iter_provider_events(gateway, messages, tools or None):
            delta = getattr(event, "delta", "") or ""
            if delta:
                yield delta
            call = getattr(event, "tool_call", None)
            if call is not None:
                tool_calls.append(call)

        if not tool_calls:
            return

        if rounds >= max_rounds:
            yield TOOL_ROUND_LIMIT_MESSAGE
            return

        # 回填模型给出的工具调用，再逐个执行并回填结果，进入下一轮
        messages.append(
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [_to_openai_tool_call(call) for call in tool_calls],
            }
        )
        for call in tool_calls:
            outcome = await executor.execute(
                ToolInvocation(
                    id=getattr(call, "id", "") or "",
                    name=getattr(call, "name", "") or "",
                    arguments=getattr(call, "arguments", "") or "{}",
                ),
                ctx,
            )
            messages.append(
                {"role": "tool", "tool_call_id": outcome.invocation_id, "content": outcome.content}
            )
            logger.info(
                "工具调用完成 tool=%s ok=%s degraded=%s attempts=%d 耗时=%.0fms",
                outcome.name,
                outcome.ok,
                outcome.degraded,
                outcome.attempts,
                outcome.duration_ms,
            )
