"""链路上下文单测（M5-5.9）：并发协程隔离是核心正确性要求。

离线运行；重点验证两个协程各自 ``bind`` 不同 trace_id 时互不干扰（contextvar
在创建 Task 时复制上下文 → 天然按协程隔离）。
"""

from __future__ import annotations

import asyncio

from app.trace.context import bind, current, new_trace_id, reset


class TestTraceIdGeneration:
    def test_new_trace_id_is_unique_hex(self) -> None:
        first, second = new_trace_id(), new_trace_id()
        assert first != second
        assert len(first) == 32 and all(ch in "0123456789abcdef" for ch in first)


class TestBindCurrentReset:
    def test_bind_then_current_then_reset(self) -> None:
        assert current().trace_id == ""  # 默认无链路
        tokens = bind("t-1", "span-parent")
        try:
            ctx = current()
            assert ctx.trace_id == "t-1"
            assert ctx.parent_span_id == "span-parent"
        finally:
            reset(tokens)
        assert current().trace_id == ""

    def test_default_parent_span_is_empty(self) -> None:
        tokens = bind("t-2")
        try:
            assert current().parent_span_id == ""
        finally:
            reset(tokens)


class TestConcurrencyIsolation:
    async def test_concurrent_coroutines_do_not_share_trace(self) -> None:
        """两个协程各自绑定不同 trace_id，等待后再读，必须互不串号。"""
        seen: dict[str, str] = {}

        async def worker(name: str, trace_id: str) -> None:
            tokens = bind(trace_id)
            try:
                await asyncio.sleep(0.01)  # 让另一协程在此期间也完成绑定
                seen[name] = current().trace_id
            finally:
                reset(tokens)

        await asyncio.gather(worker("a", "trace-a"), worker("b", "trace-b"))
        assert seen == {"a": "trace-a", "b": "trace-b"}

    async def test_bind_does_not_leak_to_sibling_child_task(self) -> None:
        """父任务绑定后创建的子任务继承上下文；但父任务复位后新任务应回到空值。"""
        tokens = bind("trace-parent")
        inherited: dict[str, str] = {}

        async def child() -> None:
            inherited["value"] = current().trace_id

        await asyncio.create_task(child())  # 创建时复制当前上下文 → 可见 trace-parent
        reset(tokens)
        after: dict[str, str] = {}

        async def child_after() -> None:
            after["value"] = current().trace_id

        await asyncio.create_task(child_after())
        assert inherited["value"] == "trace-parent"
        assert after["value"] == ""
