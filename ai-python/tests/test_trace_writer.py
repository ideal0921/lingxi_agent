"""Span 异步批量落库单测（M5-5.9）：批量、失败隔离、停机不丢数据。

全部离线运行：用假连接对象替代真实 pymysql（经 ``connect`` 注入），不连数据库。
"""

from __future__ import annotations

import asyncio
from typing import Self

from app.trace.span import Span
from app.trace.writer import TraceWriter


# 假 DB：executemany 把写入的行记录到共享 list，供断言
class _FakeCursor:
    def __init__(self, store: list) -> None:
        self._store = store

    def executemany(self, sql: str, rows: list) -> None:
        self._store.extend(list(rows))

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_: object) -> bool:
        return False


class _FakeConnection:
    def __init__(self, store: list) -> None:
        self._store = store

    def cursor(self) -> _FakeCursor:
        return _FakeCursor(self._store)

    def commit(self) -> None:
        pass

    def close(self) -> None:
        pass


def _span(index: int) -> Span:
    return Span(span_id=f"s-{index}", trace_id="t", component="HTTP", entry="/x", started_at=index)


def _writer(store: list, **kwargs: object) -> TraceWriter:
    """构造启用态 writer，注入假连接工厂（每次 connect 复用同一假连接）。"""
    connection = _FakeConnection(store)
    return TraceWriter(enabled=True, connect=lambda: connection, **kwargs)


class TestDisabled:
    async def test_enabled_false_is_noop(self) -> None:
        writer = TraceWriter(enabled=False)
        await writer.start()
        writer.record(_span(1))
        await writer.stop()
        assert writer.enabled is False
        assert writer.dropped == 0

    async def test_missing_db_host_disables_writer(self) -> None:
        """DB 配置缺失（host 为空）同样整体 no-op，避免无 DB 环境启动失败。"""
        writer = TraceWriter(enabled=True, host="")
        assert writer.enabled is False


class TestBatchFlush:
    async def test_full_batch_is_flushed_immediately(self) -> None:
        store: list = []
        writer = _writer(store, batch_size=3, flush_interval_seconds=10.0)
        await writer.start()
        try:
            for index in range(3):
                writer.record(_span(index))
            await asyncio.sleep(0.05)  # 让后台 flush 收集满一批并写出
        finally:
            await writer.stop()
        assert len(store) == 3

    async def test_interval_flush_writes_partial_batch(self) -> None:
        store: list = []
        writer = _writer(store, batch_size=100, flush_interval_seconds=0.05)
        await writer.start()
        try:
            writer.record(_span(1))
            writer.record(_span(2))
            await asyncio.sleep(0.2)  # 等待定时 flush
        finally:
            await writer.stop()
        assert len(store) == 2

    async def test_stop_flushes_pending_without_loss(self) -> None:
        """停机必须 drain 队列：未及批量触发的 span 不得丢失。"""
        store: list = []
        writer = _writer(store, batch_size=100, flush_interval_seconds=10.0)
        await writer.start()
        for index in range(5):
            writer.record(_span(index))
        await writer.stop()  # 队列中 5 条应在停机时一次写出
        assert len(store) == 5
        rows = [row[0] for row in store]
        assert rows == [f"s-{index}" for index in range(5)]


class TestFailureIsolation:
    async def test_write_failure_is_swallowed(self) -> None:
        """底层写库抛异常只告警，绝不向上抛出（旁路红线）。"""

        def _bad_connect() -> object:
            raise RuntimeError("db down")

        writer = TraceWriter(
            enabled=True, connect=_bad_connect, batch_size=1, flush_interval_seconds=0.01
        )
        await writer.start()
        writer.record(_span(1))  # batch_size=1 → flush 立刻尝试写并失败
        await asyncio.sleep(0.05)
        await writer.stop()  # stop 期间再次尝试写（drain）也不应抛出


class TestBackpressure:
    async def test_queue_full_drops_and_counts(self) -> None:
        """队列满（未启动消费）时丢弃并计数，绝不阻塞调用方。"""
        store: list = []
        writer = _writer(store, queue_size=2)  # 不 start → 无后台消费
        for index in range(5):
            writer.record(_span(index))  # 同步调用，不应阻塞
        assert writer.dropped == 3
