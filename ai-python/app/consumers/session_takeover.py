"""接管通知消费（M4-4.5）：Java 接管/转人工成功后通知本侧终止正在生成的回答。

链路::

    Java 坐席接管 / AI 转人工成功
      → 发布 lingxi.session.takeover
      → 本消费者写入「中断标记集合」（app/agent/handoff.InterruptRegistry）
      → 生成循环每片段前检查，命中即停止产出、发 interrupted 终帧、不落库半截回答

**独立消费组**（``lingxi-ai-takeover``）：与对话消费组隔离，避免位点相互影响；
该消费者运行在**独立协程**中，因此即便对话消费协程正忙于长生成，接管通知也能被及时处理。
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import Settings

logger = logging.getLogger(__name__)


async def handle_session_takeover(
    payload: dict[str, Any],
    *,
    interrupt_registry: Any,
) -> None:
    """处理一条接管通知：标记会话中断（非法消息忽略，不抛错）。"""
    session_id = str(payload.get("session_id") or "").strip()
    if not session_id:
        logger.warning("忽略非法的接管通知（缺少 session_id）: %s", payload)
        return
    reason = str(payload.get("reason") or "agent_takeover")
    agent_id = str(payload.get("agent_id") or "")
    interrupt_registry.mark(session_id, reason)
    logger.info(
        "收到接管通知，标记生成中断 sessionId=%s reason=%s agentId=%s",
        session_id,
        reason,
        agent_id or "-",
    )


async def run_session_takeover_consumer(
    *,
    config: Settings,
    interrupt_registry: Any = None,
) -> None:
    """启动接管通知消费循环（阻塞）。"""
    from app.messaging.kafka import consume_forever

    if interrupt_registry is None:
        from app.runtime import get_interrupt_registry

        interrupt_registry = await get_interrupt_registry()

    async def _handler(payload: dict[str, Any]) -> None:
        await handle_session_takeover(payload, interrupt_registry=interrupt_registry)

    await consume_forever(
        topic=config.kafka_topic_session_takeover,
        group_id=config.kafka_takeover_consumer_group,
        bootstrap_servers=config.kafka_bootstrap_servers,
        handler=_handler,
        settle=None,
        max_poll_interval_ms=config.kafka_max_poll_interval_ms,
        restart_backoff_seconds=config.kafka_consumer_restart_backoff_seconds,
    )
