"""Kafka 消费活性单测（M4-4.5 加固，离线运行，不连 Kafka）。

背景：``handler`` 单条处理超过 ``max_poll_interval_ms`` 会被踢出消费组，而 aiokafka
**不会自动重新入组**——表现为「消息再也消费不到」且**无任何异常日志**（真实故障）。
故消费循环必须在消费者异常退出/迭代结束时**退避重建**，仅在 ``settle`` 触发时退出。
"""

from __future__ import annotations

import asyncio

from aiokafka import TopicPartition

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


class _PartitionRecord:
    """带分区信息的上行消息（并行路径需 topic/partition/offset）。"""

    def __init__(self, value: str, *, partition: int = 0, offset: int = 0) -> None:
        self.value = value.encode("utf-8")
        self.topic = "t"
        self.partition = partition
        self.offset = offset


class _FakeParallelConsumer:
    """假并行消费者：``getmany`` 按脚本返回批次，``commit`` 记录提交位点。"""

    def __init__(self, batches, commit_failures: int = 0) -> None:
        self._batches = list(batches)
        self._index = 0
        self.started = False
        self.stopped = False
        self.commits: list[dict] = []
        self.commit_failures = commit_failures  # >0 时前 N 次提交抛错（模拟重平衡/网络抖动）

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    async def getmany(self, *, timeout_ms: int = 0, max_records=None) -> dict:
        if self._index < len(self._batches):
            batch = self._batches[self._index]
            self._index += 1
            return batch
        await asyncio.sleep(0.01)  # 无新消息：避免忙等
        return {}

    async def commit(self, offsets=None) -> None:
        # 模仿 aiokafka 的真实校验（aiokafka.util.commit_structure_validate）：
        # metadata 必须是字符串，传 None 会抛 TypeError——曾因此导致工作协程静默死亡。
        for meta in (offsets or {}).values():
            if not isinstance(meta.metadata, str):
                raise TypeError("Metadata should be a string")
        if self.commit_failures > 0:
            self.commit_failures -= 1
            raise RuntimeError("模拟提交失败：组重平衡")
        self.commits.append(dict(offsets or {}))


class TestParallelConsumption:
    """并行消费（M5-5.2 并发优化）：跨分区并行、分区内有序、并发受限、按序提交。"""

    async def test_processes_partitions_concurrently(self, monkeypatch) -> None:
        """不同分区的消息应被并发处理（而不是串行排队）。"""
        tp0, tp1 = TopicPartition("t", 0), TopicPartition("t", 1)
        consumer = _FakeParallelConsumer(
            [
                {
                    tp0: [_PartitionRecord('{"n": 0}', partition=0, offset=0)],
                    tp1: [_PartitionRecord('{"n": 1}', partition=1, offset=0)],
                }
            ]
        )
        _patch_consumer(monkeypatch, lambda *a, **k: consumer)

        state = {"current": 0, "max": 0, "processed": 0}
        done = asyncio.Event()

        async def _handler(payload: dict) -> None:
            state["current"] += 1
            state["max"] = max(state["max"], state["current"])
            await asyncio.sleep(0.05)  # 模拟检索 + LLM 的长任务
            state["current"] -= 1
            state["processed"] += 1
            if state["processed"] >= 2:
                done.set()

        async def _settle() -> None:
            await done.wait()

        async with asyncio.timeout(3):
            await consume_forever(
                topic="t",
                group_id="g",
                bootstrap_servers="localhost:9092",
                handler=_handler,
                settle=_settle,
                restart_backoff_seconds=0.01,
                workers=2,
            )

        assert state["max"] >= 2, "不同分区应并发处理（并发度 > 1）"

    async def test_preserves_in_partition_order_and_commits_in_order(self, monkeypatch) -> None:
        """同一分区内必须严格有序；位点按序提交且不越过未处理消息。"""
        tp = TopicPartition("t", 0)
        consumer = _FakeParallelConsumer(
            [
                {
                    tp: [
                        _PartitionRecord('{"n": 0}', offset=0),
                        _PartitionRecord('{"n": 1}', offset=1),
                        _PartitionRecord('{"n": 2}', offset=2),
                    ]
                }
            ]
        )
        _patch_consumer(monkeypatch, lambda *a, **k: consumer)

        order: list[int] = []
        done = asyncio.Event()

        async def _handler(payload: dict) -> None:
            order.append(int(payload["n"]))
            if len(order) >= 3:
                done.set()

        async def _settle() -> None:
            await done.wait()

        async with asyncio.timeout(3):
            await consume_forever(
                topic="t",
                group_id="g",
                bootstrap_servers="localhost:9092",
                handler=_handler,
                settle=_settle,
                restart_backoff_seconds=0.01,
                workers=3,
            )

        assert order == [0, 1, 2], "分区内必须严格有序（会话内有序的前提）"
        committed = [next(iter(commit.values())).offset for commit in consumer.commits if tp in commit]
        assert committed == [1, 2, 3], "应按序提交（offset+1），不越过未处理消息"

    async def test_respects_worker_limit(self, monkeypatch) -> None:
        """并发上限受 ``workers`` 封顶（保护下游 LLM）。"""
        tps = [TopicPartition("t", i) for i in range(3)]
        batch = {
            tp: [_PartitionRecord(f'{{"n": {i}}}', partition=i, offset=0)]
            for i, tp in enumerate(tps)
        }
        consumer = _FakeParallelConsumer([batch])
        _patch_consumer(monkeypatch, lambda *a, **k: consumer)

        state = {"current": 0, "max": 0, "processed": 0}
        done = asyncio.Event()

        async def _handler(payload: dict) -> None:
            state["current"] += 1
            state["max"] = max(state["max"], state["current"])
            await asyncio.sleep(0.05)
            state["current"] -= 1
            state["processed"] += 1
            if state["processed"] >= 3:
                done.set()

        async def _settle() -> None:
            await done.wait()

        async with asyncio.timeout(3):
            await consume_forever(
                topic="t",
                group_id="g",
                bootstrap_servers="localhost:9092",
                handler=_handler,
                settle=_settle,
                restart_backoff_seconds=0.01,
                workers=2,
            )

        assert state["max"] == 2, "3 个分区但 workers=2，同时在处理数应恰为 2"
        assert state["processed"] == 3, "全部消息都需被处理（超限者排队而非丢弃）"

    async def test_commit_failure_does_not_kill_worker(self, monkeypatch) -> None:
        """提交失败不得杀死工作协程——否则该分区后续消息永不被处理（真实故障形态）。"""
        tp = TopicPartition("t", 0)
        consumer = _FakeParallelConsumer(
            [
                {
                    tp: [
                        _PartitionRecord('{"n": 0}', offset=0),
                        _PartitionRecord('{"n": 1}', offset=1),
                        _PartitionRecord('{"n": 2}', offset=2),
                    ]
                }
            ],
            commit_failures=1,  # 首次提交失败
        )
        _patch_consumer(monkeypatch, lambda *a, **k: consumer)

        processed: list[int] = []
        done = asyncio.Event()

        async def _handler(payload: dict) -> None:
            processed.append(int(payload["n"]))
            if len(processed) >= 3:
                done.set()

        async def _settle() -> None:
            await done.wait()

        async with asyncio.timeout(3):
            await consume_forever(
                topic="t",
                group_id="g",
                bootstrap_servers="localhost:9092",
                handler=_handler,
                settle=_settle,
                restart_backoff_seconds=0.01,
                workers=2,  # 并行路径（单分区，验证提交失败不影响后续处理）
            )

        assert processed == [0, 1, 2], "一次提交失败后必须继续处理该分区后续消息"
        assert len(consumer.commits) == 2, "失败的提交不计入成功提交（其余 2 条提交成功）"
