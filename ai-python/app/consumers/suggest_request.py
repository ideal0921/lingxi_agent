"""同步通道的异步降级消费者（M4-4.6）。

当快捷回复同步通道**过载或超时**时，Java 侧会把请求投入 ``lingxi.ai.suggest.request``；
本消费者生成候选后投递 ``lingxi.ai.suggest.result``（key=session_id），由 Go 网关
**只推给该会话的坐席端连接**，工作台据此渲染候选。

要点：
- **唯一入队点**：仅 Java 投递（Python 侧降级不自投），避免同一请求被重复处理；
- 与同步路径共用同一套提示词与解析（:mod:`app.agent.suggest`），保证两条路径结果一致；
- 异常不影响消费循环（由 ``consume_forever`` 兜底）。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from app.agent.state import ChatMessage
from app.agent.suggest import build_suggest_prompt, new_suggest_masker, parse_candidates
from app.config import Settings

logger = logging.getLogger(__name__)


async def handle_suggest_request(
    payload: dict[str, Any],
    *,
    gateway: Any,
    producer: Any,
    config: Settings,
) -> None:
    """处理一条异步降级请求：生成候选并投递到 ``lingxi.ai.suggest.result``。"""
    session_id = str(payload.get("session_id") or "").strip()
    request_id = str(payload.get("request_id") or "").strip()
    if not session_id or not request_id:
        logger.warning("忽略非法的建议请求事件（缺 session_id 或 request_id）: %s", payload)
        return
    if producer is None or gateway is None:
        return

    max_candidates = int(payload.get("max_suggestions") or 0) or config.suggest_max_candidates
    masker = new_suggest_masker(
        enabled=config.pii_enabled, mask_address=config.pii_mask_address
    )
    history: list[ChatMessage] = []
    for turn in payload.get("history") or []:
        if not isinstance(turn, dict):
            continue
        role = str(turn.get("role") or "")
        content = str(turn.get("content") or "")
        if role in ("user", "agent") and content:
            history.append(ChatMessage(role=role, content=masker.mask(content)))
    prompt = build_suggest_prompt(
        masker.mask(str(payload.get("summary") or "")),
        history,
        masker.mask(str(payload.get("intent") or "")),
        max_candidates,
    )

    try:
        text = await asyncio.wait_for(
            gateway.generate(prompt), timeout=config.suggest_timeout_seconds
        )
    except Exception as exc:  # noqa: BLE001 - 单条失败不影响消费循环
        logger.warning("异步建议生成失败 requestId=%s: %s", request_id, exc)
        return

    candidates = [masker.restore(item) for item in parse_candidates(text, max_candidates)]
    if not candidates:
        logger.warning("异步建议生成为空 requestId=%s", request_id)
        return
    await producer.publish(
        config.kafka_topic_ai_suggest_result,
        {"session_id": session_id, "request_id": request_id, "suggestions": candidates},
        key=session_id,  # 会话键分区：与同会话事件保序
    )
    logger.info(
        "异步建议已投递 requestId=%s sessionId=%s 候选=%d", request_id, session_id, len(candidates)
    )


async def run_suggest_request_consumer(
    *, config: Settings, producer: Any, gateway: Any
) -> None:
    """启动异步降级请求消费循环（阻塞）。"""
    from app.messaging.kafka import consume_forever

    async def _handler(payload: dict[str, Any]) -> None:
        await handle_suggest_request(payload, gateway=gateway, producer=producer, config=config)

    await consume_forever(
        topic=config.kafka_topic_ai_suggest_request,
        group_id=config.kafka_suggest_consumer_group,
        bootstrap_servers=config.kafka_bootstrap_servers,
        handler=_handler,
        max_poll_interval_ms=config.kafka_max_poll_interval_ms,
        restart_backoff_seconds=config.kafka_consumer_restart_backoff_seconds,
    )
