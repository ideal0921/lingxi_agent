"""Kafka 消费活性单测（M4-4.5 加固，离线运行，不连 Kafka）。

背景：``handler`` 单条处理超过 ``max_poll_interval_ms`` 会被踢出消费组，而 aiokafka
**不会自动重新入组**——表现为「消息再也消费不到」且**无任何异常日志**（真实故障）。
故消费循环必须在消费者异常退出/迭代结束时**退避重建**，仅在 ``settle`` 触发时退出。
"""

from __future__ import annotations

import asyncio

from app.messaging import kafka as kafka_module
from app.messaging.kafka import consume_forever


class _FakeConsumer:
    """假消费者：按脚本产出消息后结束迭代（模拟被踢出/连接中断）。"""

    def __init__(self, records=None) -> None:
        self._records = list(records or [])
        self.started = False
        self.stopped = False

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    def __aiter__(self):
        async def _gen():
            for record in self._records:
                yield record

        return _gen()


class _Record:
    def __init__(self, value: str, offset: int = 0) -> None:
        self.value = value.encode("utf-8")
        self.offset = offset


def _patch_consumer(monkeypatch, factory) -> None:
    """替换消费循环实际使用的消费者类（模块级 `from aiokafka import AIOKafkaConsumer`，
    故需 patch 本模块的同名属性）。"""
    monkeypatch.setattr(kafka_module, "AIOKafkaConsumer", factory)


class TestConsumerResilience:
    async def test_rebuilds_after_consumer_error(self, monkeypatch) -> None:
        """消费者构造/启动抛异常 → 退避后重建，而非静默失效。"""
        attempts = {"count": 0}

        def _factory(*args, **kwargs):
            attempts["count"] += 1
            if attempts["count"] == 1:
                raise ConnectionError("消费组不可用")
            return _FakeConsumer()

        _patch_consumer(monkeypatch, _factory)

        async def _settle() -> None:
            await asyncio.sleep(0.05)  # 首次重建后触发停机

        async with asyncio.timeout(3):
            await consume_forever(
                topic="t1",
                group_id="g1",
                bootstrap_servers="localhost:9092",
                handler=lambda payload: asyncio.sleep(0),
                settle=_settle,
                restart_backoff_seconds=0.01,
            )

        assert attempts["count"] >= 2, "消费者异常后应重建（至少再次尝试构造）"

    async def test_rebuilds_when_iteration_ends(self, monkeypatch) -> None:
        """消费迭代意外结束（如被踢出组）→ 重建。"""
        created: list[_FakeConsumer] = []

        def _factory(*args, **kwargs):
            consumer = _FakeConsumer()  # 无消息：迭代立即结束
            created.append(consumer)
            return consumer

        _patch_consumer(monkeypatch, _factory)

        async def _settle() -> None:
            await asyncio.sleep(0.05)

        async with asyncio.timeout(3):
            await consume_forever(
                topic="t2",
                group_id="g2",
                bootstrap_servers="localhost:9092",
                handler=lambda payload: asyncio.sleep(0),
                settle=_settle,
                restart_backoff_seconds=0.01,
            )

        assert len(created) >= 2, "迭代结束后应重建消费者"
        assert all(consumer.stopped for consumer in created), "重建前必须关闭上一个消费者"

    async def test_settle_exits_without_rebuild(self, monkeypatch) -> None:
        """``settle`` 触发即正常退出（停机不得触发重建）。"""
        created: list[_FakeConsumer] = []

        def _factory(*args, **kwargs):
            consumer = _FakeConsumer()
            created.append(consumer)
            return consumer

        _patch_consumer(monkeypatch, _factory)

        async def _settle() -> None:
            return

        async with asyncio.timeout(3):
            await consume_forever(
                topic="t3",
                group_id="g3",
                bootstrap_servers="localhost:9092",
                handler=lambda payload: asyncio.sleep(0),
                settle=_settle,
                restart_backoff_seconds=0.01,
            )

        assert len(created) == 1, "停机路径不应重建消费者"

    async def test_handler_failure_does_not_break_loop(self, monkeypatch) -> None:
        """单条处理失败不得中断消费（异常隔离）。"""

        def _factory(*args, **kwargs):
            return _FakeConsumer([_Record('{"a": 1}', offset=1), _Record("not-json", offset=2)])

        _patch_consumer(monkeypatch, _factory)

        def _handler(payload: dict) -> None:
            raise RuntimeError("处理失败")

        async def _settle() -> None:
            await asyncio.sleep(0.05)

        async with asyncio.timeout(3):
            await consume_forever(
                topic="t4",
                group_id="g4",
                bootstrap_servers="localhost:9092",
                handler=_handler,
                settle=_settle,
                restart_backoff_seconds=0.01,
            )
