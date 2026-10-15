"""工作流加载器（M4-4.8）：版本解析 + 定义解析 + 已编译图缓存。

分层缓存（均为进程内 TTL/LRU，避免每次对话都打 Java gRPC 或重编译图）：

1. **快照缓存**（TTL 短，默认 30s）：版本计数与灰度规则 → 灰度变更 ≤TTL 生效；
2. **已编译图缓存**（TTL 长，默认 300s + 容量上限）：按 ``(workflow_id, version)`` 复用，
   图编译非平凡，不可每请求重建。

任一环节失败都返回 ``None``（由 :mod:`app.agent.workflow.run` 回落内置图），
**绝不向调用方抛错**——编排不可用不能拖垮问答主链路。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from app.agent.workflow.cache import TtlLruCache
from app.agent.workflow.models import (
    WorkflowDefinition,
    WorkflowDefinitionError,
    WorkflowSnapshot,
    parse_definition,
)
from app.agent.workflow.router import resolve_version

if TYPE_CHECKING:  # 仅类型标注：保持本模块可离线导入
    from app.agent.graph import LLMGenerator
    from app.agent.tools.executor import ToolExecutor
    from app.agent.tools.registry import ToolRegistry
    from app.agent.workflow.client import WorkflowClient
    from app.rag.retrieve import Retriever

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class PreparedWorkflow:
    """本轮实际要执行的工作流（版本 + 定义 + 已编译图）。"""

    workflow_id: str
    version: int
    definition: WorkflowDefinition
    graph: Any


class WorkflowLoader:
    """按版本加载工作流图，并缓存快照与已编译图。"""

    def __init__(
        self,
        client: WorkflowClient,
        *,
        snapshot_ttl_seconds: float = 30.0,
        graph_ttl_seconds: float = 300.0,
        graph_cache_size: int = 32,
        definition_max_bytes: int = 262144,
        expression_max_length: int = 200,
        tool_max_rounds: int = 3,
    ) -> None:
        self._client = client
        self._definitions = TtlLruCache[WorkflowDefinition](
            max_size=graph_cache_size * 2, ttl_seconds=graph_ttl_seconds
        )
        self._graphs = TtlLruCache[Any](max_size=graph_cache_size, ttl_seconds=graph_ttl_seconds)
        self._snapshots = TtlLruCache[WorkflowSnapshot](
            max_size=graph_cache_size, ttl_seconds=snapshot_ttl_seconds
        )
        self._definition_max_bytes = definition_max_bytes
        self._expression_max_length = expression_max_length
        self._tool_max_rounds = tool_max_rounds

    async def load_snapshot(self, workflow_id: str) -> WorkflowSnapshot | None:
        """取工作流快照（带 TTL 缓存）；失败返回 ``None``。"""
        cached = self._snapshots.get(workflow_id)
        if cached is not None:
            return cached
        try:
            snapshot = await self._client.get_workflow(workflow_id)
        except Exception:
            logger.warning("读取工作流快照失败，本轮回落内置图 workflowId=%s", workflow_id, exc_info=True)
            return None
        self._snapshots.put(workflow_id, snapshot)
        return snapshot

    async def prepare(
        self,
        workflow_id: str,
        session_id: str,
        *,
        gateway: LLMGenerator,
        retriever: Retriever | None,
        tool_registry: ToolRegistry | None,
        tool_executor: ToolExecutor | None,
    ) -> PreparedWorkflow | None:
        """解析本轮应执行的版本并给出已编译图；任一环节不可用返回 ``None``。

        版本由 :func:`app.agent.workflow.router.resolve_version` 按会话稳定分桶决定
        （灰度版 / 稳定版）。
        """
        snapshot = await self.load_snapshot(workflow_id)
        if snapshot is None:
            return None
        version = resolve_version(session_id, snapshot)
        if version <= 0:
            logger.info("工作流尚未发布，回落内置图 workflowId=%s", workflow_id)
            return None

        definition = await self._load_definition(workflow_id, version)
        if definition is None:
            return None
        graph = self._graphs.get(_graph_key(workflow_id, version))
        if graph is None:
            graph = self._compile(workflow_id, version, definition, gateway, retriever,
                                  tool_registry, tool_executor)
            if graph is None:
                return None
            self._graphs.put(_graph_key(workflow_id, version), graph)
        return PreparedWorkflow(
            workflow_id=workflow_id, version=version, definition=definition, graph=graph
        )

    async def _load_definition(self, workflow_id: str, version: int) -> WorkflowDefinition | None:
        """取指定版本快照并解析为图定义（带缓存）；失败返回 ``None``。"""
        key = _graph_key(workflow_id, version)
        cached = self._definitions.get(key)
        if cached is not None:
            return cached
        try:
            raw = await self._client.get_version(workflow_id, version)
            definition = parse_definition(raw, self._definition_max_bytes)
        except Exception:
            logger.warning(
                "加载工作流版本失败，本轮回落内置图 workflowId=%s version=%d",
                workflow_id,
                version,
                exc_info=True,
            )
            return None
        self._definitions.put(key, definition)
        return definition

    def _compile(
        self,
        workflow_id: str,
        version: int,
        definition: WorkflowDefinition,
        gateway: LLMGenerator,
        retriever: Retriever | None,
        tool_registry: ToolRegistry | None,
        tool_executor: ToolExecutor | None,
    ) -> Any | None:
        """把图定义编译为 LangGraph 图；编译失败返回 ``None``（回落内置图）。"""
        from app.agent.workflow.builder import build_workflow_graph

        try:
            graph = build_workflow_graph(
                definition,
                gateway=gateway,
                retriever=retriever,
                tool_registry=tool_registry,
                tool_executor=tool_executor,
                tool_max_rounds=self._tool_max_rounds,
                expression_max_length=self._expression_max_length,
            )
        except WorkflowDefinitionError:
            logger.warning(
                "工作流图编译失败（定义问题），本轮回落内置图 workflowId=%s version=%d",
                workflow_id,
                version,
                exc_info=True,
            )
            return None
        except Exception:
            logger.exception(
                "工作流图编译异常，本轮回落内置图 workflowId=%s version=%d", workflow_id, version
            )
            return None
        return graph


def _graph_key(workflow_id: str, version: int) -> str:
    """缓存键：工作流 + 版本（版本冻结后内容不可变，故键无需带图内容）。"""
    return f"{workflow_id}#{version}"
