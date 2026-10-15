"""消费会话消息事件并驱动流式生成（M3-3.8 流式全链路）。

链路（《架构设计》5.2，异步主路径）::

    Java 落库后发布 lingxi.session.message ──▶ 本消费者
      → stream_workflow_or_default（M4-4.8：按工作流版本加载编排图；未接入编排时回落内置图，
        两者均为「混合检索 + 精排 + 流式生成」）
      → 逐片段发布 lingxi.ai.chunk（Go 消费后经 WSS 推给前端 → 打字机）
      → 结束后发布 lingxi.ai.reply（Java 消费后持久化为助手消息，携带权威 seq）

**消息 id 约定**：助手消息 id 由本侧生成（``ai-<uuid>``），在 chunk 与 reply 中保持
一致；前端按该 id 归并「流式内容」与「Java 回推的权威消息（含 seq）」，避免重复渲染。

**终帧约定**：无论生成成功与否都会发送 ``finished=true`` 的终帧（失败时携带
``error``），前端据此结束打字机态，不会永远停留在「输入中」。

**租户/知识库兜底**：会话事件未携带上下文时使用配置默认值。

**上下文窗口（M3-3.11）**：事件携带「会话摘要 + 近 N 轮历史」，本侧保持近 N 轮原文并把
更早的旧消息压缩为摘要经 ``lingxi.session.summary`` 回写 Java 会话，形成分层记忆。
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any
from uuid import uuid4

from app.agent.handoff import decide_handoff
from app.agent.memory import merge_profile, summarize_history
from app.agent.state import ChatMessage, StreamKind
from app.agent.workflow import stream_workflow_or_default
from app.config import Settings
from app.messaging.kafka import KafkaProducer

logger = logging.getLogger(__name__)

# 事件历史条目允许的角色（其余忽略，避免脏数据进入提示词）
_ALLOWED_ROLES = ("user", "agent", "system")


@dataclass(slots=True)
class SessionMessageEvent:
    """``lingxi.session.message`` 事件体（M3-3.8；M3-3.11 增上下文；M4-4.4 增用户标识）。"""

    session_id: str
    content: str
    message_id: str = ""  # 用户消息 id（用于链路追踪与回复关联）
    seq: int = 0
    tenant_id: str = ""
    user_id: str = ""  # 用户标识（M4-4.4：用户级长期记忆按 tenant+user 隔离）
    kb_id: str = ""
    summary: str = ""  # 会话长程摘要（超出窗口的旧消息压缩结果）
    history: list[ChatMessage] = field(default_factory=list)  # 近 N 轮对话（升序，不含本条）

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> SessionMessageEvent:
        """从 Kafka 消息体构造事件；缺失会话标识或内容视为非法（由调用方兜底）。"""
        return cls(
            session_id=str(payload.get("session_id", "")),
            content=str(payload.get("content", "")),
            message_id=str(payload.get("message_id", "")),
            seq=int(payload.get("seq", 0) or 0),
            tenant_id=str(payload.get("tenant_id", "") or ""),
            user_id=str(payload.get("user_id", "") or ""),
            kb_id=str(payload.get("kb_id", "") or ""),
            summary=str(payload.get("summary", "") or ""),
            history=_parse_history(payload.get("history")),
        )


def _parse_history(raw: Any) -> list[ChatMessage]:
    """解析事件中的近 N 轮历史；非法条目跳过（不因脏数据中断应答）。"""
    if not isinstance(raw, list):
        return []
    messages: list[ChatMessage] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role", ""))
        content = str(item.get("content", ""))
        if role in _ALLOWED_ROLES and content:
            messages.append(ChatMessage(role=role, content=content))
    return messages


def build_chunk_payload(
    session_id: str,
    message_id: str,
    index: int,
    delta: str,
    *,
    finished: bool,
    error: str = "",
    interrupted: bool = False,
    reason: str = "",
) -> dict[str, Any]:
    """构造流式片段事件体（对 Go 与前端稳定的契约）。

    ``interrupted``/``reason``（M4-4.5）仅在**终帧**有值：表示本轮因人工接管而中断。
    与 ``error`` 分开表达——「已转人工」是正常业务态，不应被前端渲染成错误横幅。
    """
    return {
        "session_id": session_id,
        "message_id": message_id,
        "index": index,
        "delta": delta,
        "finished": finished,
        "error": error,
        "interrupted": interrupted,
        "reason": reason,
    }


def build_handoff_payload(
    session_id: str, tenant_id: str, message_id: str, reason: str, seq: int = 0
) -> dict[str, Any]:
    """构造转人工请求事件体（M4-4.5，供 Java 把会话转入 ``WAITING_HUMAN``）。"""
    return {
        "session_id": session_id,
        "tenant_id": tenant_id,
        "message_id": message_id,
        "reason": reason,
        "seq": seq,
    }


def build_reply_payload(
    session_id: str, tenant_id: str, message_id: str, content: str, reply_to: str
) -> dict[str, Any]:
    """构造最终回答事件体（供 Java 持久化为助手消息）。"""
    return {
        "session_id": session_id,
        "tenant_id": tenant_id,
        "message_id": message_id,
        "content": content,
        "reply_to": reply_to,
    }


async def handle_session_message(
    payload: dict[str, Any],
    *,
    config: Settings,
    producer: KafkaProducer,
    retriever: Any = None,
    gateway: Any = None,
    tool_registry: Any = None,
    tool_executor: Any = None,
    memory_store: Any = None,
    interrupt_registry: Any = None,
) -> None:
    """单条会话消息的完整处理：流式生成 + 片段下发 + 最终回答回写。

    Args:
        payload: ``lingxi.session.message`` 消息体。
        config: 应用配置（主题名、租户/知识库兜底值）。
        producer: Kafka 生产者。
        retriever: 检索器（缺省为 ``None`` 表示不检索）。
        gateway: LLM 网关（必须实现 ``stream``）。
        tool_registry / tool_executor: 工具白名单与执行器（M4-4.1/4.2）；
            ``None`` 时按 ``agent_tools_enabled`` 从运行时惰性装配（未启用则不装配）。
        memory_store: 用户级长期记忆存储（M4-4.4）；``None`` 时按 ``agent_memory_enabled``
            从运行时惰性装配，装配/读写失败一律降级为「无记忆」。
        interrupt_registry: 生成中断标记集合（M4-4.5）；``None`` 时取运行时单例。
    """
    event = SessionMessageEvent.from_payload(payload)
    if not event.session_id or not event.content.strip():
        logger.warning("忽略非法的会话消息事件（缺少 session_id 或 content）: %s", payload)
        return

    tenant_id = event.tenant_id or config.agent_default_tenant_id
    kb_id = event.kb_id or config.agent_default_kb_id or None
    ai_message_id = f"ai-{uuid4().hex}"
    chunk_topic = config.kafka_topic_ai_chunk

    # 工具链路（M4-4.1/4.2）：启用时惰性装配注册表与执行器（含审计出口）。
    # 装配失败（如业务 gRPC 未就绪）**不得影响问答主链路**，降级为无工具继续应答。
    tool_context = None
    if config.agent_tools_enabled:
        try:
            from app.agent.tools.spec import ToolContext
            from app.runtime import get_tool_executor, get_tool_registry

            if tool_registry is None:
                tool_registry = await get_tool_registry()
            if tool_executor is None:
                tool_executor = await get_tool_executor()
            tool_context = ToolContext(
                tenant_id=tenant_id, session_id=event.session_id, message_id=event.message_id
            )
        except Exception:
            logger.exception("工具链路装配失败，本轮降级为无工具应答 sessionId=%s", event.session_id)
            tool_registry, tool_executor, tool_context = None, None, None

    # 跨会话长期记忆（M4-4.4）：按 tenant+user 取用户画像并注入提示词。
    # 需同时具备开关、用户标识与存储；任一缺失或读写异常都降级为「无记忆」，不阻断问答。
    memory = ""
    if config.agent_memory_enabled and event.user_id:
        try:
            if memory_store is None:
                from app.runtime import get_memory_store

                memory_store = await get_memory_store()
            memory = await memory_store.get_profile(tenant_id, event.user_id)
            logger.info(
                "用户长期记忆已注入 sessionId=%s userId=%s 长度=%d",
                event.session_id,
                event.user_id,
                len(memory),
            )
        except Exception:
            logger.exception("用户长期记忆读取失败，本轮降级为无记忆 userId=%s", event.user_id)
            memory = ""

    # 生成中断标记（M4-4.5）：由接管通知消费协程写入，本循环读取。
    # 处理本条消息前先清一次，消解「迟到」的接管事件，避免误伤本轮生成。
    if interrupt_registry is None:
        from app.runtime import get_interrupt_registry

        interrupt_registry = await get_interrupt_registry()
    interrupt_registry.clear(event.session_id)

    collected: list[str] = []
    index = 0
    error = ""
    started = False
    handoff_reason = ""  # 非空表示命中转人工规则（跳过模型生成）
    interrupted_reason = ""  # 非空表示被人工接管中断（停止产出、不落库半截回答）
    # 阶段计时（观测首字延迟瓶颈：检索 / LLM 首字 / 总体）
    started_at = time.perf_counter()
    retrieve_ms: float | None = None
    first_delta_ms: float | None = None
    try:
        async for agent_event in stream_workflow_or_default(
            event.content,
            gateway=gateway,
            tenant_id=tenant_id,
            kb_id=kb_id,
            history=event.history,  # 近 N 轮原文（M3-3.11）
            summary=event.summary,  # 超出窗口的旧对话摘要（M3-3.11）
            memory=memory,  # 跨会话用户长期记忆（M4-4.4）
            retriever=retriever,
            tool_registry=tool_registry,
            tool_executor=tool_executor,
            tool_context=tool_context,
            tool_max_rounds=config.tool_max_rounds,
            # 多工作流运行时（M4-4.8）：按工作流版本加载编排图；不可用时内部回落内置图
            workflow_id=config.agent_default_workflow_id,
            session_id=event.session_id,
            config=config,
        ):
            if agent_event.kind is StreamKind.CONTEXT:
                retrieve_ms = (time.perf_counter() - started_at) * 1000
                # 转人工规则判定（M4-4.5）：在**首次产出片段之前**决定，命中则 break
                # 关闭生成器——此后不会发生任何 LLM 调用与工具调用。
                decision = _decide_handoff(
                    event.content, agent_event.contexts, config, retriever_configured=retriever is not None
                )
                if decision.handoff:
                    handoff_reason = decision.reason
                    break
                continue
            if agent_event.kind is not StreamKind.DELTA or not agent_event.delta:
                continue
            # 接管中断检查（M4-4.5）：每个片段前查一次，命中即停止产出（最多多发 1 片）
            interrupted_reason = interrupt_registry.check(event.session_id)
            if interrupted_reason:
                logger.info(
                    "检测到人工接管，终止生成 sessionId=%s reason=%s 已产出片段=%d",
                    event.session_id,
                    interrupted_reason,
                    index,
                )
                break
            if first_delta_ms is None:
                first_delta_ms = (time.perf_counter() - started_at) * 1000
            started = True
            collected.append(agent_event.delta)
            await producer.publish(
                chunk_topic,
                build_chunk_payload(
                    event.session_id, ai_message_id, index, agent_event.delta, finished=False
                ),
                key=event.session_id,  # 会话键分区：保证片段有序
            )
            index += 1
    except Exception as exc:  # 生成失败也要发终帧，前端据此收尾
        error = f"生成失败：{exc}"
        logger.exception("会话 %s 流式生成失败", event.session_id)

    # 命中转人工规则：不下发模型回答，改回固定话术（复用流式通道，用户端打字机体验一致）
    if handoff_reason and not error:
        notice = config.agent_handoff_reply_text
        if notice:
            started = True
            collected.append(notice)
            await producer.publish(
                chunk_topic,
                build_chunk_payload(event.session_id, ai_message_id, index, notice, finished=False),
                key=event.session_id,
            )
            index += 1
        logger.info(
            "命中转人工规则，未生成 AI 回答 sessionId=%s reason=%s", event.session_id, handoff_reason
        )

    logger.info(
        "流式完成 sessionId=%s 检索=%.0fms 首字=%.0fms 片段=%d 总耗时=%.0fms%s",
        event.session_id,
        retrieve_ms if retrieve_ms is not None else -1.0,
        first_delta_ms if first_delta_ms is not None else -1.0,
        index,
        (time.perf_counter() - started_at) * 1000,
        f" 中断={interrupted_reason}" if interrupted_reason else "",
    )

    # 终帧：结束打字机态（失败也发，携带 error；被接管中断时携带 interrupted/reason）
    await producer.publish(
        chunk_topic,
        build_chunk_payload(
            event.session_id,
            ai_message_id,
            index,
            "",
            finished=True,
            error=error,
            interrupted=bool(interrupted_reason),
            reason=interrupted_reason,
        ),
        key=event.session_id,
    )

    # 被接管中断时不回写最终回答（否则半截 AI 回答会被 Java 落库成权威助手消息）
    if (started or collected) and not interrupted_reason:
        await producer.publish(
            config.kafka_topic_ai_reply,
            build_reply_payload(
                event.session_id, tenant_id, ai_message_id, "".join(collected), event.message_id
            ),
            key=event.session_id,
        )

    # 通知 Java 转人工（M4-4.5）：Java 消费后把会话转入 WAITING_HUMAN（仍在客服队列、AI 退出）
    if handoff_reason and producer is not None:
        await producer.publish(
            config.kafka_topic_ai_handoff,
            build_handoff_payload(
                event.session_id, tenant_id, ai_message_id, handoff_reason, event.seq
            ),
            key=event.session_id,
        )

    # 上下文窗口（M3-3.11）：超出窗口的旧消息压缩为摘要并回写 Java 会话（幂等覆盖）
    # 消费活性（M4-4.5 加固）：摘要/画像合并属**非关键路径**且可能触发多次 LLM 调用，
    # 若在 handler 内 await，最坏耗时（providers × retries × timeout）会超过 Kafka
    # max_poll.interval.ms 而被踢出消费组（且 aiokafka 不会自动重新入组）——
    # 故改为**后台任务**，handler 立即返回，消费循环持续 poll。
    _spawn_background(
        publish_summary_if_needed(
            event, gateway=gateway, producer=producer, config=config, store=memory_store
        )
    )


# 后台记忆维护任务（防被 GC 提前回收，并在异常时显式记录——否则异常会被静默吞掉）
_background_tasks: set[asyncio.Task] = set()


def _spawn_background(coro: Any) -> None:
    """把协程作为后台任务执行（不阻塞消费循环；异常显式记录）。"""
    try:
        task = asyncio.create_task(coro)
    except RuntimeError:  # 无运行中的事件循环（同步调用场景）：退化为直接关闭协程
        logger.warning("无事件循环，跳过后台记忆维护任务")
        coro.close()
        return
    _background_tasks.add(task)

    def _done(finished: asyncio.Task) -> None:
        _background_tasks.discard(finished)
        if finished.cancelled():
            return
        exc = finished.exception()
        if exc is not None:
            logger.error("后台记忆维护任务异常", exc_info=exc)

    task.add_done_callback(_done)


def _decide_handoff(
    query: str, hits: Any, config: Settings, *, retriever_configured: bool
) -> Any:
    """按配置执行转人工规则判定（M4-4.5；未启用时直接返回不转人工）。

    **低置信度仅在配置了检索器时才生效**：未配置知识库时「未检索到命中」是常态，
    不能据此判定低置信度（否则无知识库的会话会被全部转人工）。用户明示与情绪规则
    不受此限制。

    检索命中的 ``score`` 可能是精排分或 RRF 分（量纲不同，不可比），故「低分阈值」规则
    默认关闭，仅默认启用「命中为空」这一与量纲无关的信号。
    """
    from app.agent.handoff import HandoffDecision

    if not config.agent_handoff_enabled:
        return HandoffDecision(False, "")
    scores = [float(getattr(hit, "score", 0.0) or 0.0) for hit in (hits or [])]
    return decide_handoff(
        query,
        scores,
        user_keywords=config.agent_handoff_user_keywords,
        emotion_keywords=config.agent_handoff_emotion_keywords,
        emotion_min_hits=config.agent_handoff_emotion_min_hits,
        empty_hits_enabled=config.agent_handoff_empty_hits_enabled and retriever_configured,
        low_score_enabled=config.agent_handoff_low_score_enabled and retriever_configured,
        low_score_threshold=config.agent_handoff_low_score_threshold,
    )


async def publish_summary_if_needed(
    event: SessionMessageEvent,
    *,
    gateway: Any,
    producer: KafkaProducer,
    config: Settings,
    store: Any = None,
) -> None:
    """当历史超出上下文窗口时压缩旧消息并投递会话摘要，同时顺带合并用户长期画像。

    仅当 ``len(history) > agent_history_max_messages``（确有消息落到窗口之外）时才触发，
    因此普通短会话不会产生额外的模型调用与事件。

    **跨会话长期记忆（M4-4.4）复用同一触发点**：同一段 ``overflow`` 既用于会话摘要，
    也用于画像合并——非超窗时两者都不产生调用，超窗时共 2 次（摘要 + 画像）。

    Args:
        store: 用户级长期记忆存储；``None`` 或未开启合并/无用户标识时跳过画像写入。
    """
    if not config.agent_summary_enabled or gateway is None:
        return
    window = config.agent_history_max_messages
    if window <= 0 or len(event.history) <= window:
        return
    overflow = event.history[: len(event.history) - window]
    summary = await summarize_history(
        gateway,
        overflow,
        previous_summary=event.summary,
        max_chars=config.agent_summary_max_chars,
        timeout_seconds=config.agent_summary_timeout_seconds,  # 非关键路径硬性超时兜底
    )
    if summary and summary != event.summary:
        await producer.publish(
            config.kafka_topic_session_summary,
            {"session_id": event.session_id, "summary": summary},
            key=event.session_id,
        )
        logger.info(
            "会话摘要已回写 sessionId=%s 压缩消息数=%d 摘要长度=%d",
            event.session_id,
            len(overflow),
            len(summary),
        )

    # 跨会话长期记忆（M4-4.4）：同一触发点顺带把本轮要点合并进用户画像（读最新→合并→覆盖写）
    if not (config.agent_memory_merge_enabled and event.user_id and store is not None):
        return
    tenant_id = event.tenant_id or config.agent_default_tenant_id
    try:
        previous = await store.get_profile(tenant_id, event.user_id)
        updated = await merge_profile(
            gateway,
            previous,
            overflow,
            max_chars=config.agent_memory_max_chars,
            timeout_seconds=config.agent_summary_timeout_seconds,  # 非关键路径硬性超时兜底
        )
        if updated and updated != previous:
            await store.save_profile(tenant_id, event.user_id, updated)
            logger.info(
                "用户长期记忆已合并 userId=%s 长度=%d", event.user_id, len(updated)
            )
    except Exception:  # 画像合并失败不得影响摘要投递与问答主链路
        logger.exception("用户长期记忆合并失败（已跳过） userId=%s", event.user_id)


async def run_session_message_consumer(
    *,
    config: Settings,
    producer: KafkaProducer,
    retriever: Any,
    gateway: Any,
    settle: Any = None,
) -> None:
    """启动会话消息消费循环（阻塞）。"""
    from app.messaging.kafka import consume_forever

    async def _handler(payload: dict[str, Any]) -> None:
        await handle_session_message(
            payload, config=config, producer=producer, retriever=retriever, gateway=gateway
        )

    await consume_forever(
        topic=config.kafka_topic_session_message,
        group_id=config.kafka_consumer_group,
        bootstrap_servers=config.kafka_bootstrap_servers,
        handler=_handler,
        settle=settle,
        max_poll_interval_ms=config.kafka_max_poll_interval_ms,
        restart_backoff_seconds=config.kafka_consumer_restart_backoff_seconds,
    )
