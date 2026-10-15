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


async def get_memory_store() -> Any:
    """用户级长期记忆存储单例（M4-4.4：Redis KV，连接池只建一次）。"""
    return await _get("memory_store", _build_memory_store)


async def _build_memory_store() -> Any:
    from app.agent.memory_store import UserProfileStore

    return UserProfileStore(
        host=settings.redis_host,
        port=settings.redis_port,
        db=settings.redis_db,
        password=settings.redis_password,
        ttl_seconds=settings.agent_memory_ttl_seconds,
        timeout_seconds=settings.agent_memory_timeout_seconds,
        protocol=settings.redis_protocol,
    )


async def get_interrupt_registry() -> Any:
    """生成中断标记集合单例（M4-4.5：接管通知写入、生成循环读取；纯内存）。"""
    return await _get("interrupt_registry", _build_interrupt_registry)


async def _build_interrupt_registry() -> Any:
    from app.agent.handoff import InterruptRegistry

    return InterruptRegistry(ttl_seconds=settings.agent_handoff_interrupt_ttl_seconds)


async def get_suggest_gate() -> Any:
    """快捷回复并发闸门单例（M4-4.6：gRPC 同步通道与统计端点共用同一实例）。"""
    return await _get("suggest_gate", _build_suggest_gate)


async def _build_suggest_gate() -> Any:
    from app.agent.suggest import ConcurrencyGate

    return ConcurrencyGate(
        limit=settings.suggest_gate, queue_wait_seconds=settings.suggest_queue_wait_seconds
    )


async def get_business_client() -> Any:
    """Java 业务域 gRPC 客户端单例（M4-4.2：工具调用复用同一 channel）。"""
    return await _get("business_client", _build_business_client)


async def _build_business_client() -> Any:
    from app.agent.tools.business import BusinessToolClient

    return BusinessToolClient(
        settings.business_grpc_target, timeout_seconds=settings.business_grpc_timeout_seconds
    )


async def get_tool_registry() -> Any:
    """工具注册表（白名单）单例（M4-4.1）。"""
    client = await get_business_client()  # 锁外解析依赖，避免锁嵌套
    return await _get("tool_registry", lambda: _build_tool_registry(client))


async def _build_tool_registry(client: Any) -> Any:
    """构建工具注册表（异步工厂：``_get`` 会 await 工厂返回值）。"""
    from app.agent.tools.business import build_business_tools
    from app.agent.tools.registry import ToolRegistry

    return ToolRegistry(build_business_tools(client, degrade_message=settings.business_degrade_message))


async def get_tool_executor() -> Any:
    """工具执行器单例（M4-4.1/4.3：含超时重试降级与审计出口）。"""
    registry = await get_tool_registry()  # 锁外解析依赖，避免锁嵌套
    producer = await get_producer() if settings.kafka_enabled else None
    return await _get("tool_executor", lambda: _build_tool_executor(registry, producer))


async def _build_tool_executor(registry: Any, producer: Any) -> Any:
    """构建工具执行器（异步工厂：``_get`` 会 await 工厂返回值）。"""
    from app.agent.tools.audit import ToolAuditor
    from app.agent.tools.executor import ToolExecutor

    auditor = ToolAuditor(
        producer=producer,
        topic=settings.kafka_topic_tool_audit,
        enabled=settings.tool_audit_enabled,
    )
    return ToolExecutor(
        registry,
        auditor=auditor,
        default_timeout_seconds=settings.tool_timeout_seconds,
        default_max_retries=settings.tool_max_retries,
        degrade_message=settings.business_degrade_message,
    )


async def get_workflow_client() -> Any:
    """Java 编排域 gRPC 客户端单例（M4-4.8：复用同一 channel 读取版本与灰度规则）。"""
    return await _get("workflow_client", _build_workflow_client)


async def _build_workflow_client() -> Any:
    from app.agent.workflow.client import WorkflowClient

    return WorkflowClient(
        settings.agent_workflow_grpc_target,
        timeout_seconds=settings.agent_workflow_grpc_timeout_seconds,
    )


async def get_workflow_loader() -> Any:
    """工作流加载器单例（M4-4.8：快照/定义/已编译图的分层缓存）。"""
    client = await get_workflow_client()  # 锁外解析依赖，避免锁嵌套
    return await _get("workflow_loader", lambda: _build_workflow_loader(client))


async def _build_workflow_loader(client: Any) -> Any:
    from app.agent.workflow.loader import WorkflowLoader

    return WorkflowLoader(
        client,
        snapshot_ttl_seconds=settings.agent_workflow_snapshot_ttl_seconds,
        graph_ttl_seconds=settings.agent_workflow_graph_ttl_seconds,
        graph_cache_size=settings.agent_workflow_graph_cache_size,
        definition_max_bytes=settings.agent_workflow_max_definition_bytes,
        expression_max_length=settings.agent_workflow_max_expression_length,
        tool_max_rounds=settings.tool_max_rounds,
    )


async def shutdown() -> None:
    """释放运行时资源（Kafka 生产者、业务工具 gRPC 通道、编排域 gRPC 通道、记忆 Redis 连接池）。

    供 FastAPI lifespan 停机调用。
    """
    producer = _singletons.pop("producer", None)
    if producer is not None:
        await producer.stop()
    business_client = _singletons.pop("business_client", None)
    if business_client is not None:
        business_client.close()
    workflow_client = _singletons.pop("workflow_client", None)
    if workflow_client is not None:
        workflow_client.close()
    _singletons.pop("workflow_loader", None)
    memory_store = _singletons.pop("memory_store", None)
    if memory_store is not None:
        await memory_store.close()
