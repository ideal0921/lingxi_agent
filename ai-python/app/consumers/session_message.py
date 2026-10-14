"""消费会话消息事件并驱动流式生成（M3-3.8 流式全链路）。

链路（《架构设计》5.2，异步主路径）::

    Java 落库后发布 lingxi.session.message ──▶ 本消费者
      → stream_answer（混合检索 + 精排 + 流式生成）
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

import logging
import time
from dataclasses import dataclass, field
from typing import Any
from uuid import uuid4

from app.agent.graph import stream_answer
from app.agent.memory import summarize_history
from app.agent.state import ChatMessage, StreamKind
from app.config import Settings
from app.messaging.kafka import KafkaProducer

logger = logging.getLogger(__name__)

# 事件历史条目允许的角色（其余忽略，避免脏数据进入提示词）
_ALLOWED_ROLES = ("user", "agent", "system")


@dataclass(slots=True)
class SessionMessageEvent:
    """``lingxi.session.message`` 事件体（M3-3.8；M3-3.11 增上下文字段）。"""

    session_id: str
    content: str
    message_id: str = ""  # 用户消息 id（用于链路追踪与回复关联）
    seq: int = 0
    tenant_id: str = ""
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
    session_id: str, message_id: str, index: int, delta: str, *, finished: bool, error: str = ""
) -> dict[str, Any]:
    """构造流式片段事件体（对 Go 与前端稳定的契约）。"""
    return {
        "session_id": session_id,
        "message_id": message_id,
        "index": index,
        "delta": delta,
        "finished": finished,
        "error": error,
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
) -> None:
    """单条会话消息的完整处理：流式生成 + 片段下发 + 最终回答回写。

    Args:
        payload: ``lingxi.session.message`` 消息体。
        config: 应用配置（主题名、租户/知识库兜底值）。
        producer: Kafka 生产者。
        retriever: 检索器（缺省为 ``None`` 表示不检索）。
        gateway: LLM 网关（必须实现 ``stream``）。
    """
    event = SessionMessageEvent.from_payload(payload)
    if not event.session_id or not event.content.strip():
        logger.warning("忽略非法的会话消息事件（缺少 session_id 或 content）: %s", payload)
        return

    tenant_id = event.tenant_id or config.agent_default_tenant_id
    kb_id = event.kb_id or config.agent_default_kb_id or None
    ai_message_id = f"ai-{uuid4().hex}"
    chunk_topic = config.kafka_topic_ai_chunk

    collected: list[str] = []
    index = 0
    error = ""
    started = False
    # 阶段计时（观测首字延迟瓶颈：检索 / LLM 首字 / 总体）
    started_at = time.perf_counter()
    retrieve_ms: float | None = None
    first_delta_ms: float | None = None
    try:
        async for agent_event in stream_answer(
            event.content,
            gateway=gateway,
            tenant_id=tenant_id,
            kb_id=kb_id,
            history=event.history,  # 近 N 轮原文（M3-3.11）
            summary=event.summary,  # 超出窗口的旧对话摘要（M3-3.11）
            retriever=retriever,
        ):
            if agent_event.kind is StreamKind.CONTEXT:
                retrieve_ms = (time.perf_counter() - started_at) * 1000
                continue
            if agent_event.kind is not StreamKind.DELTA or not agent_event.delta:
                continue
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

    logger.info(
        "流式完成 sessionId=%s 检索=%.0fms 首字=%.0fms 片段=%d 总耗时=%.0fms",
        event.session_id,
        retrieve_ms if retrieve_ms is not None else -1.0,
        first_delta_ms if first_delta_ms is not None else -1.0,
        index,
        (time.perf_counter() - started_at) * 1000,
    )

    # 终帧：结束打字机态（失败也发，携带 error）
    await producer.publish(
        chunk_topic,
        build_chunk_payload(
            event.session_id, ai_message_id, index, "", finished=True, error=error
        ),
        key=event.session_id,
    )

    if started or collected:
        await producer.publish(
            config.kafka_topic_ai_reply,
            build_reply_payload(
                event.session_id, tenant_id, ai_message_id, "".join(collected), event.message_id
            ),
            key=event.session_id,
        )

    # 上下文窗口（M3-3.11）：超出窗口的旧消息压缩为摘要并回写 Java 会话（幂等覆盖）
    await publish_summary_if_needed(event, gateway=gateway, producer=producer, config=config)


async def publish_summary_if_needed(
    event: SessionMessageEvent,
    *,
    gateway: Any,
    producer: KafkaProducer,
    config: Settings,
) -> None:
    """当历史超出上下文窗口时压缩旧消息并投递会话摘要。

    仅当 ``len(history) > agent_history_max_messages``（确有消息落到窗口之外）时才触发，
    因此普通短会话不会产生额外的模型调用与事件。
    """
    if not config.agent_summary_enabled or gateway is None:
        return
    window = config.agent_history_max_messages
    if window <= 0 or len(event.history) <= window:
        return
    overflow = event.history[: len(event.history) - window]
    summary = await summarize_history(
        gateway, overflow, previous_summary=event.summary, max_chars=config.agent_summary_max_chars
    )
    if not summary or summary == event.summary:
        return
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
    )
