"""分析事件发布（M5-5.1）：LLM 用量埋点 → ``lingxi.analytics.event``。

链路：网关（:mod:`app.gateway.router`）在每次 LLM 调用收尾时调 :func:`flush_llm_usage`
→ 从 contextvar 取走用量（真实或估算）→ **后台任务**投递 → Java 报表域消费落库。

设计要点：
- **不阻塞流式输出**：投递走 ``asyncio.create_task`` 后台任务 + done-callback 记录异常
  （与 ``session_message`` 的后台任务约定一致）；投递失败仅告警；
- **三级降级**：真实 usage（``stream_options.include_usage``）→ 兼容重试 → 按字符数估算并置
  ``estimated=true``（``prompt_chars//4``、``completion_chars//4``）；
- **无 scope 或未启用时 no-op**：离线单测/评测/CLI 不产生任何事件。
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any
from uuid import uuid4

from app.analytics.scope import current_scope
from app.config import settings

logger = logging.getLogger(__name__)

# 字符数 → token 的粗估系数（中文 1 字≈1 token、英文约 4 字符≈1 token，取折中 4）
_CHARS_PER_TOKEN = 4

# 后台投递任务（防被 GC 提前回收，并在异常时显式记录——否则异常会被静默吞掉）
_background_tasks: set[asyncio.Task] = set()


def flush_llm_usage(*, completion_chars: int, latency_ms: int) -> None:
    """发布一次 LLM 用量分析事件（后台、尽力而为）。

    Args:
        completion_chars: 本次调用产出的文本长度（无真实 usage 时用于估算）
        latency_ms: 本次调用耗时（记录在明细事件中，供后续分析）
    """
    scope = current_scope()
    if scope is None or not settings.analytics_enabled:
        return

    usage = scope.take_usage()
    if usage:
        prompt_tokens = int(usage.get("prompt_tokens") or 0)
        completion_tokens = int(usage.get("completion_tokens") or 0)
        total_tokens = int(usage.get("total_tokens") or (prompt_tokens + completion_tokens))
        estimated = False
    else:
        # 提供方不支持 usage 帧（或流式被中断）：按字符数估算，如实标记 estimated
        prompt_tokens = scope.prompt_chars // _CHARS_PER_TOKEN
        completion_tokens = max(0, completion_chars) // _CHARS_PER_TOKEN
        total_tokens = prompt_tokens + completion_tokens
        estimated = True

    payload: dict[str, Any] = {
        "event_id": uuid4().hex,
        "type": "llm_usage",
        "tenant_id": scope.tenant_id,
        "session_id": scope.session_id,
        "occurred_at": int(time.time() * 1000),
        "message_id": scope.message_id,
        "provider": scope.provider,
        "model": scope.model,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": total_tokens,
        "estimated": estimated,
        "first_token_ms": scope.first_token_ms,
        "latency_ms": latency_ms,
    }
    _spawn_background(_publish(payload))


async def _publish(payload: dict[str, Any]) -> None:
    """投递到分析主题（复用运行时 Kafka 生产者单例）；失败仅告警。"""
    try:
        from app.runtime import get_producer

        producer = await get_producer()
        await producer.publish(
            settings.kafka_topic_analytics_event, payload, key=payload.get("session_id", "")
        )
        logger.debug(
            "LLM 用量已投递 sessionId=%s provider=%s estimated=%s",
            payload.get("session_id"),
            payload.get("provider"),
            payload.get("estimated"),
        )
    except Exception:  # 埋点属旁路，失败不得影响对话主链路
        logger.warning("LLM 用量埋点投递失败（已忽略）sessionId=%s", payload.get("session_id"),
                       exc_info=True)


def _spawn_background(coro: Any) -> None:
    """把协程作为后台任务执行（不阻塞调用方；异常显式记录）。"""
    try:
        task = asyncio.create_task(coro)
    except RuntimeError:  # 无运行中的事件循环（同步调用场景）：退化为直接关闭协程
        logger.warning("无事件循环，跳过 LLM 用量埋点投递")
        coro.close()
        return
    _background_tasks.add(task)

    def _done(finished: asyncio.Task) -> None:
        _background_tasks.discard(finished)
        if finished.cancelled():
            return
        exc = finished.exception()
        if exc is not None:
            logger.error("LLM 用量埋点后台任务异常", exc_info=exc)

    task.add_done_callback(_done)
