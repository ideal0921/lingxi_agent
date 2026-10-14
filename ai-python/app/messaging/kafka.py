"""Kafka 事件总线接入（M3-3.8 流式全链路）。

职责边界：
- :class:`KafkaProducer`：异步生产者封装（JSON 序列化 + 会话键分区 + 起停管理）；
- :func:`consume_forever`：通用消费循环（JSON 反序列化 + 逐条处理 + 异常隔离）。

设计要点：
- **键分区**：以 ``session_id`` 为消息键，保证同一会话的片段严格有序落同一分区，
  前端按序拼接不会错乱；
- **异常隔离**：单条消息处理失败**不中断**消费循环（记录堆栈后继续），避免一条
  坏消息拖垮整条链路；失败消息不阻塞 offset 推进（死信/重试留待后续里程碑）。

所有连接参数均来自配置（``LINGXI_KAFKA_*``），无硬编码。
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

logger = logging.getLogger(__name__)

MessageHandler = Callable[[dict[str, Any]], Awaitable[None]]


class KafkaProducer:
    """异步生产者：进程内单例复用，``start``/``stop`` 由生命周期管理。"""

    def __init__(self, bootstrap_servers: str) -> None:
        self._bootstrap = bootstrap_servers
        self._producer: AIOKafkaProducer | None = None

    @property
    def started(self) -> bool:
        return self._producer is not None

    async def start(self) -> None:
        if self._producer is not None:
            return
        producer = AIOKafkaProducer(bootstrap_servers=self._bootstrap)
        await producer.start()
        self._producer = producer

    async def publish(self, topic: str, payload: dict[str, Any], key: str | None = None) -> None:
        """发布一条 JSON 消息；未启动或发送失败均抛异常（由调用方决定降级策略）。"""
        if self._producer is None:
            raise RuntimeError("Kafka 生产者未启动")
        # ensure_ascii=False 保留中文原文，避免前端解码成本与可读性损失
        value = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        await self._producer.send_and_wait(
            topic, value=value, key=key.encode("utf-8") if key else None
        )

    async def stop(self) -> None:
        if self._producer is not None:
            await self._producer.stop()
            self._producer = None


async def consume_forever(
    *,
    topic: str,
    group_id: str,
    bootstrap_servers: str,
    handler: MessageHandler,
    settle: Callable[[], Awaitable[None]] | None = None,
) -> None:
    """通用消费循环（阻塞直到 ``settle`` 完成或异常）。

    Args:
        topic: 订阅主题。
        group_id: 消费者组（多实例水平扩展时同组共享分区）。
        bootstrap_servers: 集群地址。
        handler: 单条消息处理器（入参为反序列化后的字典）。
        settle: 取消等待信号，返回即退出循环（用于优雅停机）。
    """
    consumer = AIOKafkaConsumer(
        topic,
        bootstrap_servers=bootstrap_servers,
        group_id=group_id,
        auto_offset_reset="latest",  # 冷启动只消费新消息，避免重放历史
    )
    await consumer.start()
    logger.info("Kafka 消费者已启动 topic=%s group=%s", topic, group_id)

    async def _pump() -> None:
        async for record in consumer:
            try:
                payload = json.loads(record.value.decode("utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                logger.warning("跳过非法消息 topic=%s offset=%d: %s", topic, record.offset, exc)
                continue
            try:
                await handler(payload)
            except Exception:  # 单条失败不得中断消费循环
                logger.exception("消费处理失败 topic=%s offset=%d", topic, record.offset)

    try:
        if settle is None:
            await _pump()
        else:
            pump_task = asyncio.create_task(_pump())
            settle_task = asyncio.create_task(settle())
            done, pending = await asyncio.wait(
                {pump_task, settle_task}, return_when=asyncio.FIRST_COMPLETED
            )
            for task in pending:
                task.cancel()
            for task in done:
                # 消费循环异常需向上抛出；停机信号正常返回即可
                if task is pump_task:
                    task.result()
    finally:
        await consumer.stop()
        logger.info("Kafka 消费者已停止 topic=%s", topic)
