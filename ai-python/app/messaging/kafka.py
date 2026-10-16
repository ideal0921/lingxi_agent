"""Kafka 事件总线接入（M3-3.8 流式全链路）。

职责边界：
- :class:`KafkaProducer`：异步生产者封装（JSON 序列化 + 会话键分区 + 起停管理）；
- :func:`consume_forever`：通用消费循环（JSON 反序列化 + 逐条处理 + 异常隔离）。

设计要点：
- **键分区**：以 ``session_id`` 为消息键，保证同一会话的片段严格有序落同一分区，
  前端按序拼接不会错乱；
- **异常隔离**：单条消息处理失败**不中断**消费循环（记录堆栈后继续），避免一条
  坏消息拖垮整条链路；失败消息不阻塞 offset 推进（死信/重试留待后续里程碑）；
- **消费并发（M5-5.2 优化）**：``workers > 1`` 时启用**分区级并行**——每个分区一个工作
  协程（分区内严格有序），跨分区并行；因消息以 ``session_id`` 为键，**「分区内串行」即
  「会话内有序」**。并发上限由 ``workers`` 信号量封顶以保护下游 LLM。并行路径**关闭自动
  提交**并改为处理完成后**按分区手动提交**（自动提交按「拉取位点」推进，会在处理完成前
  越过未处理消息，崩溃时造成丢失）。

所有连接参数均来自配置（``LINGXI_KAFKA_*``），无硬编码。
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, OffsetAndMetadata, TopicPartition

logger = logging.getLogger(__name__)

MessageHandler = Callable[[dict[str, Any]], Awaitable[None]]

# 并行消费参数：轮询超时需**远小于** ``max_poll_interval_ms``，保证处理长任务期间仍在拉取，
# 避免被踢出消费组（对比串行路径：处理耗时直接占用 poll 间隔）。
_PARALLEL_POLL_TIMEOUT_MS = 500
# 单分区缓冲上限：满则反压（暂停拉取），避免投递远快于消费导致内存膨胀。
_PARALLEL_QUEUE_MAX = 256


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
    max_poll_interval_ms: int = 300_000,
    restart_backoff_seconds: float = 5.0,
    workers: int = 1,
) -> None:
    """通用消费循环（阻塞直到 ``settle`` 完成）。

    **消费活性加固（M4-4.5）**：``handler`` 单条处理耗时若超过 ``max_poll_interval_ms``，
    消费者会被踢出消费组，而 aiokafka **不会自动重新入组**——表现为「消息再也消费不到」
    且**没有任何异常日志**。因此：
    - 调用方应避免 ``handler`` 内出现超长任务（记忆维护已改为后台任务）；
    - 本函数在消费者异常退出（或迭代意外结束）时**记录并退避重建**，直到 ``settle`` 触发。

    Args:
        topic: 订阅主题。
        group_id: 消费者组（多实例水平扩展时同组共享分区）。
        bootstrap_servers: 集群地址。
        handler: 单条消息处理器（入参为反序列化后的字典）。
        settle: 取消等待信号，返回即退出循环（用于优雅停机）。
        max_poll_interval_ms: 两次 poll 的最大间隔（超时即被踢出组）。
        restart_backoff_seconds: 异常退出后重建消费者的退避等待（秒）。
        workers: 消费并发度。``1``（默认）为**串行逐条**（保持既有语义与自动提交）；
            ``>1`` 启用**分区级并行**（每分区一个工作协程、分区内严格有序），
            并发上限由本值封顶，实际并发为 ``min(workers, 分区数)``。
    """
    # 停机信号**只订阅一次**（重建消费者的过程中不能丢失停机语义）
    settle_event = asyncio.Event()
    watch_task: asyncio.Task | None = None

    async def _watch_settle() -> None:
        await settle()
        settle_event.set()

    if settle is not None:
        watch_task = asyncio.create_task(_watch_settle())

    try:
        while True:
            try:
                settled = await _consume_once(
                    topic=topic,
                    group_id=group_id,
                    bootstrap_servers=bootstrap_servers,
                    handler=handler,
                    settle_event=settle_event,
                    max_poll_interval_ms=max_poll_interval_ms,
                    workers=workers,
                )
            except asyncio.CancelledError:
                raise  # 停机取消：直接向上传播，不算异常
            except Exception:  # 消费者异常必须重建而非静默失效
                logger.exception(
                    "Kafka 消费循环异常退出，将在 %.1fs 后重建消费者 topic=%s group=%s",
                    restart_backoff_seconds,
                    topic,
                    group_id,
                )
                await asyncio.sleep(restart_backoff_seconds)
                continue
            if settled:
                return
            # 消费迭代意外结束（如消费者被踢出/连接中断）：同样重建，避免静默失效
            logger.warning("Kafka 消费迭代结束，准备重建消费者 topic=%s group=%s", topic, group_id)
            await asyncio.sleep(restart_backoff_seconds)
    finally:
        if watch_task is not None:
            watch_task.cancel()


async def _consume_once(
    *,
    topic: str,
    group_id: str,
    bootstrap_servers: str,
    handler: MessageHandler,
    settle_event: asyncio.Event,
    max_poll_interval_ms: int,
    workers: int = 1,
) -> bool:
    """单轮消费（一次消费者生命周期）。

    Returns:
        ``True`` 表示停机信号已触发（调用方应正常退出）；``False`` 表示消费迭代结束
        （调用方应重建消费者）。
    """
    parallel = workers > 1
    consumer = AIOKafkaConsumer(
        topic,
        bootstrap_servers=bootstrap_servers,
        group_id=group_id,
        auto_offset_reset="latest",  # 冷启动只消费新消息，避免重放历史
        max_poll_interval_ms=max_poll_interval_ms,
        # 并行路径必须关闭自动提交：自动提交按「拉取位点」推进，会在处理完成前越过未处理
        # 消息；崩溃或重建时表现为消息丢失（at-most-once）。并行路径改为处理完成后手动提交。
        enable_auto_commit=not parallel,
    )
    await consumer.start()
    logger.info(
        "Kafka 消费者已启动 topic=%s group=%s 并发=%d", topic, group_id, max(1, workers)
    )

    try:
        if parallel:
            pump_task = asyncio.create_task(_pump_parallel(consumer, topic, handler, workers))
        else:
            pump_task = asyncio.create_task(_pump_serial(consumer, topic, handler))
        waiter = asyncio.create_task(settle_event.wait())
        done, pending = await asyncio.wait({pump_task, waiter}, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        if waiter in done:
            return True
        # 消费循环异常需向上抛出；正常结束返回 False（由调用方重建）
        pump_task.result()
        return False
    finally:
        await consumer.stop()
        logger.info("Kafka 消费者已停止 topic=%s", topic)


def _decode(topic: str, record: Any) -> dict[str, Any] | None:
    """反序列化消息体；非法消息返回 ``None``（仅告警，不中断消费）。"""
    try:
        return json.loads(record.value.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        logger.warning("跳过非法消息 topic=%s offset=%d: %s", topic, record.offset, exc)
        return None


async def _pump_serial(consumer: Any, topic: str, handler: MessageHandler) -> None:
    """串行消费（默认路径）：逐条 ``await``，位点由消费者自动提交。"""
    async for record in consumer:
        payload = _decode(topic, record)
        if payload is None:
            continue
        try:
            await handler(payload)
        except Exception:
            logger.exception("消费处理失败 topic=%s offset=%d", topic, record.offset)


async def _pump_parallel(consumer: Any, topic: str, handler: MessageHandler, workers: int) -> None:
    """分区级并行消费（``workers > 1``）。

    每个分区对应一个工作协程，从该分区队列按序取消息处理——**分区内严格有序**；跨分区并行。
    因消息以 ``session_id`` 为键，同会话消息必落同一分区，故「分区内串行」即「会话内有序」。
    全局信号量 ``workers`` 限制同时在处理的条数，保护下游 LLM 不被压垮。

    位点提交：分区内按拉取顺序处理，故每条处理完成后提交该条位点即**不会越过未处理消息**
    （这正是并行路径关闭自动提交的原因）。处理失败同样推进位点——与串行路径既有语义一致
    （失败不阻塞后续消息，死信/重试留待后续里程碑）。

    已知限制：分区在运行期被重平衡（如扩缩消费者实例）时，本协程不感知归属变化；本平台
    按单实例消费运行，重平衡仅在实例数变化时发生，届时旧位点可能被重复投递（at-least-once）。
    """
    queues: dict[TopicPartition, asyncio.Queue] = {}
    worker_tasks: list[asyncio.Task] = []
    gate = asyncio.Semaphore(workers)

    async def _worker(tp: TopicPartition, queue: asyncio.Queue) -> None:
        while True:
            record = await queue.get()
            payload = _decode(topic, record)
            if payload is not None:
                async with gate:  # 并发上限：超出则排队，避免同时打爆下游
                    try:
                        await handler(payload)
                    except Exception:
                        logger.exception("消费处理失败 topic=%s offset=%d", topic, record.offset)
            try:
                # metadata 必须是**字符串**：aiokafka 的 commit 校验拒绝 None（抛 TypeError）。
                await consumer.commit({tp: OffsetAndMetadata(record.offset + 1, "")})
            except Exception:
                # 提交失败（组重平衡/网络抖动）不得杀掉工作协程：位点未推进，消息将在下次拉取
                # 或重启后按 at-least-once 重放。**必须显式记录**——否则协程静默死亡、该分区
                # 后续消息永不被处理（真实踩坑：表现为「部分会话无应答且无任何错误日志」）。
                logger.exception(
                    "提交位点失败 topic=%s partition=%d offset=%d", topic, tp.partition, record.offset
                )

    def _on_worker_done(task: asyncio.Task) -> None:
        """工作协程异常退出必须显式告警（fire-and-forget 任务的异常默认无人取回）。"""
        if task.cancelled():
            return
        exc = task.exception()
        if exc is not None:
            logger.error("Kafka 分区工作协程异常退出 topic=%s", topic, exc_info=exc)

    try:
        while True:
            # 轮询超时远小于 max_poll_interval_ms：处理长任务期间仍在拉取，不会被踢出消费组
            batches = await consumer.getmany(
                timeout_ms=_PARALLEL_POLL_TIMEOUT_MS, max_records=_PARALLEL_QUEUE_MAX
            )
            for tp, records in batches.items():
                queue = queues.get(tp)
                if queue is None:
                    queue = asyncio.Queue(maxsize=_PARALLEL_QUEUE_MAX)
                    queues[tp] = queue
                    task = asyncio.create_task(_worker(tp, queue))
                    task.add_done_callback(_on_worker_done)
                    worker_tasks.append(task)
                    logger.info("Kafka 分区工作协程已启动 topic=%s partition=%d", topic, tp.partition)
                for record in records:
                    await queue.put(record)  # 缓冲满则反压：暂停拉取，不丢消息
    finally:
        for task in worker_tasks:
            task.cancel()
