"""进程内运行时单例（M3-3.8）：Milvus 存储 / 检索器 / Agent 图 / Kafka 生产者。

FastAPI 路由与 Kafka 消费者共享同一组单例，避免重复建连（Milvus 客户端、Kafka
连接各只建一次）。全部**惰性装配**（首次使用时才建立连接），并配双检锁防并发
重复构建（首个并发请求不会各自建连）。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from app.config import settings

logger = logging.getLogger(__name__)

_singletons: dict[str, Any] = {}
_lock = asyncio.Lock()


async def _get(name: str, factory: Callable[[], Awaitable[Any]]) -> Any:
    """双检锁惰性构建并缓存单例。

    **调用约束**：``factory`` 内不得再调用 :func:`_get`（``asyncio.Lock`` 不可重入，
    会自锁死）；有依赖的单例须先在锁外解析依赖，再把已解析对象传入工厂（见
    :func:`get_retriever` / :func:`get_agent`）。
    """
    if name not in _singletons:
        async with _lock:
            if name not in _singletons:
                _singletons[name] = await factory()
                logger.info("运行时单例已装配: %s", name)
    return _singletons[name]


async def _build_store() -> Any:
    # 函数内导入：避免应用启动即拉起 Milvus SDK
    from app.rag.index.writer import ChunkStore

    return ChunkStore(
        uri=settings.milvus_uri,
        chunk_collection=settings.milvus_chunk_collection,
        dim=settings.embed_dim,
        version=settings.milvus_collection_version,
    )


async def get_store() -> Any:
    """Milvus 子块存储单例。"""
    return await _get("store", _build_store)


async def get_retriever() -> Any:
    """混合检索器单例（供 Agent 图与流式生成复用）。"""
    store = await get_store()  # 锁外解析依赖，避免锁嵌套
    return await _get("retriever", lambda: _build_retriever(store))


async def _build_retriever(store: Any) -> Any:
    from app.rag.retrieve import create_retriever

    return create_retriever(store, settings)


async def get_agent() -> Any:
    """已编译的 Agent 图单例（非流式接口使用）。"""
    retriever = await get_retriever()  # 锁外解析依赖，避免锁嵌套
    return await _get("agent", lambda: _build_agent(retriever))


async def _build_agent(retriever: Any) -> Any:
    from app.agent.graph import build_graph
    from app.gateway.router import gateway

    return build_graph(retriever=retriever, gateway=gateway)


async def _build_embedder() -> Any:
    from app.rag.ingest.embedder import create_embedder

    return create_embedder(settings)


async def get_embedder() -> Any:
    """Embedding 单例（供入库管道复用：云端客户端长连接与本地分词器只建一次）。"""
    return await _get("embedder", _build_embedder)


async def _build_producer() -> Any:
    from app.messaging.kafka import KafkaProducer

    producer = KafkaProducer(settings.kafka_bootstrap_servers)
    await producer.start()
    return producer


async def get_producer() -> Any:
    """Kafka 生产者单例。"""
    return await _get("producer", _build_producer)


async def shutdown() -> None:
    """释放运行时资源（Kafka 生产者），供 FastAPI lifespan 停机调用。"""
    producer = _singletons.pop("producer", None)
    if producer is not None:
        await producer.stop()
