"""Span 异步批量落库（M5-5.9）：有界队列 + 后台 flush，绝不阻断请求与消费循环。

设计取舍（均围绕「落库一律旁路」这一红线）：
- **旁路**：写库为同步 IO（pymysql），放进**线程池**执行（``asyncio.to_thread``），
  避免阻塞事件循环、放大首字延迟；
- **批量**：满 ``batch_size`` 立即写；否则最多等 ``flush_interval_seconds`` 后写，
  摊薄网络往返；
- **有界**：队列满即丢弃并计数告警——宁可丢 span 也不阻塞主链路；
- **兜底**：写库异常只记日志，绝不向上抛出；停机时 drain 队列，保证**已入队数据不丢**。

``enabled=False``（开关关闭）或 DB 配置缺失（host 为空）时**整体 no-op**，
保证无 DB 环境（离线单测/本地开发）也能正常启动。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from contextlib import suppress
from typing import Any

import pymysql

from app.config import settings
from app.trace.span import COLUMNS, Span

logger = logging.getLogger(__name__)

# 批量 INSERT 语句：列名由 COLUMNS 统一生成，避免与 Span.to_row 顺序漂移
_INSERT_SQL = (
    f"INSERT INTO lingxi_api_trace ({', '.join(COLUMNS)}) "
    f"VALUES ({', '.join(['%s'] * len(COLUMNS))})"
)

# 丢弃告警的打印间隔：队列持续满时避免每条都打日志刷屏
_DROP_LOG_EVERY = 100


class TraceWriter:
    """进程内单例：``start``/``stop`` 由 FastAPI 生命周期管理。"""

    def __init__(
        self,
        *,
        enabled: bool,
        host: str = "127.0.0.1",
        port: int = 3306,
        user: str = "root",
        password: str = "",
        database: str = "lingxi",
        batch_size: int = 100,
        flush_interval_seconds: float = 2.0,
        queue_size: int = 4096,
        connect: Callable[[], Any] | None = None,
    ) -> None:
        # DB 配置缺失（host 为空）同样视为禁用：避免无 DB 环境启动即失败
        self._enabled = bool(enabled) and bool(host)
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database
        self._batch_size = max(1, batch_size)
        self._interval = max(0.001, flush_interval_seconds)
        self._connect = connect  # 允许测试注入假连接工厂
        self._queue: asyncio.Queue[tuple] = asyncio.Queue(maxsize=max(1, queue_size))
        self._flush_task: asyncio.Task[None] | None = None
        self._dropped = 0

    @property
    def enabled(self) -> bool:
        return self._enabled

    @property
    def dropped(self) -> int:
        """累计丢弃条数（队列满时计数，供告警/观测）。"""
        return self._dropped

    async def start(self) -> None:
        """启动后台 flush 任务（幂等；禁用时 no-op）。"""
        if not self._enabled or self._flush_task is not None:
            return
        self._flush_task = asyncio.create_task(self._flush_loop())
        # 后台任务异常若不取回会被 asyncio 静默吞掉，导致「追踪悄悄停写」
        self._flush_task.add_done_callback(self._on_flush_done)

    def record(self, span: Span) -> None:
        """非阻塞入队（同步方法，供中间件/消费循环直接调用）；禁用时 no-op。"""
        if not self._enabled:
            return
        try:
            self._queue.put_nowait(span.to_row())
        except asyncio.QueueFull:
            # 队列满：丢弃而非等待，保护主链路；计数并周期性告警
            self._dropped += 1
            if self._dropped == 1 or self._dropped % _DROP_LOG_EVERY == 0:
                logger.warning("追踪队列已满，丢弃 span 累计=%d（不影响请求/MQ 主链路）", self._dropped)

    async def stop(self) -> None:
        """停机：停止后台任务并 drain 队列，**保证已入队 span 不丢**。"""
        if not self._enabled:
            return
        task = self._flush_task
        self._flush_task = None
        if task is not None:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
        rows = self._drain()
        if rows:
            await self._write(rows)

    def _on_flush_done(self, task: asyncio.Task[None]) -> None:
        if task.cancelled():
            return
        error = task.exception()
        if error is not None:
            logger.error("追踪 flush 后台任务异常退出", exc_info=error)

    async def _flush_loop(self) -> None:
        """循环：批量收集满一批或到时间即写。"""
        while True:
            batch = await self._collect_batch()
            if batch:
                await self._write(batch)

    async def _collect_batch(self) -> list[tuple]:
        """收集一批：满 ``batch_size`` 立即返回；否则最多等 ``flush_interval`` 返回已有条目。"""
        batch: list[tuple] = []
        loop = asyncio.get_running_loop()
        deadline = loop.time() + self._interval
        while len(batch) < self._batch_size:
            remaining = deadline - loop.time()
            if remaining <= 0:
                break
            try:
                item = await asyncio.wait_for(self._queue.get(), timeout=remaining)
            except TimeoutError:
                break
            batch.append(item)
        return batch

    def _drain(self) -> list[tuple]:
        """非阻塞取空队列（停机时冲掉残余 span）。"""
        rows: list[tuple] = []
        while True:
            try:
                rows.append(self._queue.get_nowait())
            except asyncio.QueueEmpty:
                break
        return rows

    async def _write(self, rows: list[tuple]) -> None:
        """批量落库：同步 IO 放线程池；任何异常仅告警，绝不影响调用方。"""
        try:
            await asyncio.to_thread(self._write_rows, rows)
        except Exception:
            logger.exception("追踪 span 写库失败（丢弃 %d 条，不影响主链路）", len(rows))

    def _write_rows(self, rows: list[tuple]) -> None:
        """同步写一批 span（由 :meth:`_write` 放入线程池执行）。"""
        conn = self._open()
        try:
            with conn.cursor() as cursor:
                cursor.executemany(_INSERT_SQL, rows)
            conn.commit()
        finally:
            conn.close()

    def _open(self) -> Any:
        """新建连接：测试可注入 ``connect`` 工厂，生产用 pymysql。"""
        if self._connect is not None:
            return self._connect()
        return pymysql.connect(
            host=self._host,
            port=self._port,
            user=self._user,
            password=self._password,
            database=self._database,
            charset="utf8mb4",
            autocommit=False,
        )


_writer: TraceWriter | None = None


def get_writer() -> TraceWriter:
    """进程内单例（首次调用时按配置构建；测试可用 :func:`set_writer` 替换）。"""
    global _writer
    if _writer is None:
        _writer = TraceWriter(
            enabled=settings.trace_enabled,
            host=settings.lingxi_db_host,
            port=settings.lingxi_db_port,
            user=settings.lingxi_db_user,
            password=settings.lingxi_db_password,
            database=settings.lingxi_db_name,
            batch_size=settings.trace_batch_size,
            flush_interval_seconds=settings.trace_flush_interval_seconds,
            queue_size=settings.trace_queue_size,
        )
    return _writer


def set_writer(writer: TraceWriter | None) -> None:
    """替换单例（仅供测试注入假 writer）。"""
    global _writer
    _writer = writer
