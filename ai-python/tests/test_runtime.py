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
