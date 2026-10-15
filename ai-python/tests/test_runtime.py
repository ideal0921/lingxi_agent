"""运行时单例单测（M3-3.8，离线运行，不连 Milvus / Kafka）。

重点覆盖：双检锁「只构建一次」与并发安全，以及**带依赖的单例不得在持锁时再取锁**
（``asyncio.Lock`` 不可重入，嵌套取锁会自锁死——该缺陷曾在流式链路中真实出现）。
"""

from __future__ import annotations

import asyncio
from uuid import uuid4

from app import runtime


class TestLazySingleton:
    async def test_builds_once_under_concurrency(self) -> None:
        name = f"test-{uuid4().hex}"
        calls = 0

        async def factory() -> object:
            nonlocal calls
            calls += 1
            await asyncio.sleep(0.02)  # 放大竞态窗口，确保并发首次调用会真正重叠
            return object()

        try:
            results = await asyncio.gather(*(runtime._get(name, factory) for _ in range(6)))
        finally:
            runtime._singletons.pop(name, None)

        assert calls == 1, "并发首次调用应只构建一次"
        assert all(item is results[0] for item in results), "应返回同一实例"

    async def test_dependent_singleton_does_not_deadlock(self, monkeypatch) -> None:
        """回归防护：get_retriever 先在锁外解析 store 依赖，再取锁构建自身。"""
        store_sentinel = object()
        retriever_sentinel = object()

        async def fake_build_store() -> object:
            return store_sentinel

        async def fake_build_retriever(store: object) -> object:
            assert store is store_sentinel, "检索器应拿到已解析的 store"
            return retriever_sentinel

        monkeypatch.setattr(runtime, "_build_store", fake_build_store)
        monkeypatch.setattr(runtime, "_build_retriever", fake_build_retriever)
        runtime._singletons.pop("store", None)
        runtime._singletons.pop("retriever", None)

        try:
            async with asyncio.timeout(3):  # 死锁时此处超时失败
                assert await runtime.get_retriever() is retriever_sentinel
        finally:
            runtime._singletons.pop("store", None)
            runtime._singletons.pop("retriever", None)

    async def test_tool_singletons_are_assembled_awaited(self, monkeypatch) -> None:
        """回归防护（M4-4.1）：工具单例的工厂必须是协程。

        此前 `_build_tool_registry` / `_build_tool_executor` 为同步函数，`_get` 中
        `await factory()` 会抛 "object can't be awaited"，导致工具链路在真实链路上
        静默降级为「无工具应答」（表现为模型不去查订单而直接转人工）。
        """
        from app.agent.tools.executor import ToolExecutor
        from app.agent.tools.registry import ToolRegistry

        monkeypatch.setattr(runtime.settings, "kafka_enabled", False)  # 不建 Kafka 连接
        for name in ("business_client", "tool_registry", "tool_executor"):
            runtime._singletons.pop(name, None)

        try:
            async with asyncio.timeout(5):
                registry = await runtime.get_tool_registry()
                executor = await runtime.get_tool_executor()
        finally:
            for name in ("business_client", "tool_registry", "tool_executor"):
                runtime._singletons.pop(name, None)

        assert isinstance(registry, ToolRegistry)
        assert isinstance(executor, ToolExecutor)
        assert set(registry.names()) == {"get_order", "get_logistics", "submit_return"}
        assert executor.registry is registry, "执行器应复用同一注册表实例"


class TestMemoryStoreSingleton:
    async def test_assembles_user_profile_store(self) -> None:
        """回归防护（M4-4.4）：画像存储单例惰性装配（构造阶段不建 Redis 连接）。

        工厂必须是协程（``_get`` 会 await 返回值），且装配后按 tenant+user 生成 key。
        """
        from app.agent.memory_store import UserProfileStore

        runtime._singletons.pop("memory_store", None)

        try:
            async with asyncio.timeout(5):
                store = await runtime.get_memory_store()
        finally:
            runtime._singletons.pop("memory_store", None)

        assert isinstance(store, UserProfileStore)
        assert store.profile_key("t1", "u1") == "lingxi:memory:profile:t1:u1"
        assert store.profile_key("t1", "") is None, "缺 user_id 时不产生 key"

    async def test_shutdown_releases_memory_store(self) -> None:
        """停机时应释放记忆连接池。"""
        store = _ClosableStore()
        runtime._singletons["memory_store"] = store
        try:
            async with asyncio.timeout(5):
                await runtime.shutdown()
        finally:
            runtime._singletons.pop("memory_store", None)

        assert store.closed is True


class _ClosableStore:
    def __init__(self) -> None:
        self.closed = False

    async def close(self) -> None:
        self.closed = True
