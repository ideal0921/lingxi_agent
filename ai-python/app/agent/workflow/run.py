"""多工作流运行时入口（M4-4.8）：按版本加载并执行编排图，不可用时回落内置图。

对上层（Kafka 消费链路）保持与 :func:`app.agent.graph.stream_answer` **完全一致的签名与
事件契约**（``CONTEXT → DELTA* → DONE``），因此 ``session_message`` 只需换一个调用点，
其余（中断检查、chunk 发布、终帧、``ai.reply``、转人工判定、记忆维护）零改动。

三重兜底（编排问题绝不拖垮问答）：

1. 开关关闭 / 未配置默认工作流 → 内置图；
2. 编排域不可达 / 快照读取失败 → 内置图（告警）；
3. 图定义解析或编译失败 / 目标版本取不到 → 内置图（告警）。

**顺序假设**：图内 ``retrieve`` 节点应在 ``llm`` 节点之前（与内置图同序），
以便上层在**任何片段产出前**拿到 ``CONTEXT`` 事件做转人工判定；若图中没有检索节点，
本运行器会先补发一个空上下文事件，保证「用户明示/情绪」两类规则仍然生效。
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator, Sequence

from app.agent.graph import (
    mask_history,
    new_masker,
    stream_answer,
    trim_history,
)
from app.agent.state import ChatMessage, StreamEvent, StreamKind
from app.agent.workflow.models import NodeType
from app.config import Settings, settings

logger = logging.getLogger(__name__)


async def stream_workflow_or_default(
    query: str,
    *,
    gateway: object,
    tenant_id: str = "",
    kb_id: str | None = None,
    history: Sequence[ChatMessage] | None = None,
    summary: str = "",
    memory: str = "",
    retriever: object | None = None,
    history_max_messages: int | None = None,
    tool_registry: object | None = None,
    tool_executor: object | None = None,
    tool_context: object | None = None,
    tool_max_rounds: int = 3,
    workflow_id: str = "",
    session_id: str = "",
    config: Settings | None = None,
    loader: object | None = None,
) -> AsyncIterator[StreamEvent]:
    """按工作流版本执行一轮对话；不可用时回落内置图。

    Args:
        workflow_id: 指定工作流；空则取 ``config.agent_default_workflow_id``。
        session_id: 会话标识（灰度分桶用，保证同会话稳定命中同一版本）。
        config: 应用配置；``None`` 时取全局 ``settings``。
        loader: 工作流加载器；``None`` 时从运行时单例惰性获取。
        其余参数与 :func:`app.agent.graph.stream_answer` 完全一致。
    """
    cfg = config or settings

    async def _default() -> AsyncIterator[StreamEvent]:
        """内置图路径（三重兜底统一走这里，保证行为与 M4-4.7 完全一致）。"""
        async for event in stream_answer(
            query,
            gateway=gateway,
            tenant_id=tenant_id,
            kb_id=kb_id,
            history=history,
            summary=summary,
            memory=memory,
            retriever=retriever,
            history_max_messages=history_max_messages,
            tool_registry=tool_registry,
            tool_executor=tool_executor,
            tool_context=tool_context,
            tool_max_rounds=tool_max_rounds,
        ):
            yield event

    effective_workflow = (workflow_id or cfg.agent_default_workflow_id or "").strip()
    if not cfg.agent_workflow_enabled or not effective_workflow:
        async for event in _default():
            yield event
        return

    prepared = None
    try:
        active_loader = loader or await _resolve_loader()
        if active_loader is not None:
            prepared = await active_loader.prepare(
                effective_workflow,
                session_id,
                gateway=gateway,
                retriever=retriever,
                tool_registry=tool_registry,
                tool_executor=tool_executor,
            )
    except Exception:
        logger.warning("工作流加载异常，本轮回落内置图 workflowId=%s", effective_workflow, exc_info=True)
        prepared = None

    if prepared is None:
        async for event in _default():
            yield event
        return

    logger.info(
        "按编排执行 workflowId=%s version=%d sessionId=%s",
        prepared.workflow_id,
        prepared.version,
        session_id,
    )
    limit = (
        cfg.agent_history_max_messages if history_max_messages is None else history_max_messages
    )
    masker = new_masker()
    initial_state = {
        "query": query,
        "query_masked": masker.mask(query),
        "history": mask_history(masker, trim_history(list(history or []), limit)),
        "summary": summary,
        "memory": memory,
        "tenant_id": tenant_id,
        "kb_id": kb_id,
        "session_id": session_id,
        "pii_masker": masker,
        "tool_context": tool_context,
        "workflow_id": prepared.workflow_id,
        "workflow_version": prepared.version,
    }

    has_retrieve = any(node.type is NodeType.RETRIEVE for node in prepared.definition.nodes)
    if not has_retrieve:
        # 无检索节点：先补发空上下文事件，让上层的转人工规则（用户明示/情绪）仍能生效
        yield StreamEvent(kind=StreamKind.CONTEXT, contexts=[])

    raw_events = _raw_events(prepared.graph, initial_state)
    pending_first = ""
    async for event in raw_events:
        if event.kind is StreamKind.CONTEXT:
            yield event
            continue
        if event.kind is StreamKind.DELTA and event.delta:
            pending_first = event.delta
            break

    async def _remaining_deltas() -> AsyncIterator[str]:
        """首片 + 余下片段（顺序拼成一条流，交给单条持久还原管道）。"""
        if pending_first:
            yield pending_first
        async for event in raw_events:
            if event.kind is StreamKind.CONTEXT:
                # 检索晚于生成属编排异常（顺序假设被破坏）：告警并忽略，避免打断在途回答
                logger.warning("工作流在片段产出后仍下发上下文事件，已忽略 workflowId=%s", prepared.workflow_id)
                continue
            if event.kind is StreamKind.DELTA and event.delta:
                yield event.delta

    async for delta in masker.restore_stream(_remaining_deltas()):
        yield StreamEvent(kind=StreamKind.DELTA, delta=delta)
    yield StreamEvent(kind=StreamKind.DONE)


async def _raw_events(graph: object, initial_state: dict) -> AsyncIterator[StreamEvent]:
    """把 LangGraph 自定义流映射为统一事件（节点写入的即 ``StreamEvent``）。"""
    async for chunk in graph.astream(initial_state, stream_mode="custom"):
        if isinstance(chunk, StreamEvent):
            yield chunk


async def _resolve_loader() -> object | None:
    """惰性获取工作流加载器单例；未启用编排时返回 ``None``。"""
    from app.runtime import get_workflow_loader

    return await get_workflow_loader()
