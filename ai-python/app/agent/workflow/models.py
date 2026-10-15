"""工作流图定义模型（M4-4.8）：与 Java 编排域 ``WorkflowGraph`` 同构的 JSON Schema。

图定义由 Studio 画布产出、Java 编排域在发布时**全量校验**并冻结为不可变版本快照；
运行时按版本读取后编译为 LangGraph 图。故本模块只做「结构解析」——业务规则
（成环、分支配对、必填配置）已在发布侧保证，运行期仅做防御性结构检查，不重复实现。

Schema（与 Studio、Java 共用同一份）::

    {
      "nodes": [{"id": "n1", "type": "retrieve", "name": "知识检索", "config": {...}}],
      "edges": [{"id": "e1", "source": "n1", "target": "n2", "condition": "always"}]
    }
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class WorkflowDefinitionError(ValueError):
    """图定义不可解析：结构非法、超出大小上限或 JSON 损坏。"""


class NodeType(StrEnum):
    """节点类型（与 Java ``NodeType`` 线上标识一致）。"""

    LLM = "llm"
    RETRIEVE = "retrieve"
    TOOL = "tool"
    BRANCH = "branch"


class EdgeCondition(StrEnum):
    """边条件标记：分支节点出边为 ``true``/``false``，其余为 ``always``。"""

    ALWAYS = "always"
    TRUE = "true"
    FALSE = "false"


@dataclass(slots=True)
class WorkflowNode:
    """工作流节点。

    Attributes:
        id: 节点标识（图内唯一）。
        type: 节点类型。
        name: 展示名（用于日志与来源标注）。
        config: 节点配置（开放键值，按类型取用）。
    """

    id: str
    type: NodeType
    name: str
    config: dict[str, Any] = field(default_factory=dict)

    def config_str(self, key: str) -> str | None:
        """取非空字符串配置项；缺失或全空白返回 ``None``。"""
        value = self.config.get(key)
        if value is None:
            return None
        text = str(value).strip()
        return text or None

    def config_int(self, key: str, default: int = 0) -> int:
        """取整数配置项；非法值回落默认值（不因配置脏值中断整轮对话）。"""
        value = self.config.get(key)
        if isinstance(value, bool):  # bool 是 int 子类，需显式排除
            return default
        if isinstance(value, (int, float)):
            return int(value)
        if isinstance(value, str) and value.strip().lstrip("-").isdigit():
            return int(value.strip())
        return default


@dataclass(slots=True)
class WorkflowEdge:
    """工作流连线。"""

    id: str
    source: str
    target: str
    condition: EdgeCondition = EdgeCondition.ALWAYS


@dataclass(slots=True)
class WorkflowSnapshot:
    """工作流版本与灰度规则的运行时快照（来自 Java 编排域 ``GetWorkflow``）。"""

    workflow_id: str
    published_version: int = 0
    latest_version: int = 0
    canary_version: int = 0
    canary_percent: int = 0
    status: str = ""


@dataclass(slots=True)
class WorkflowDefinition:
    """已解析的图定义（节点 + 边）。"""

    nodes: list[WorkflowNode]
    edges: list[WorkflowEdge]

    def node(self, node_id: str) -> WorkflowNode | None:
        """按 id 取节点。"""
        for item in self.nodes:
            if item.id == node_id:
                return item
        return None

    def outgoing(self, node_id: str) -> list[WorkflowEdge]:
        """某节点的出边。"""
        return [edge for edge in self.edges if edge.source == node_id]

    def entry_id(self) -> str | None:
        """入口节点（入度为 0）；无入口或多入口时返回 ``None``（由调用方降级）。"""
        targets = {edge.target for edge in self.edges}
        entries = [node.id for node in self.nodes if node.id not in targets]
        return entries[0] if len(entries) == 1 else None

    def branch_targets(self, node_id: str) -> dict[str, str]:
        """分支节点的两条出边目标：``{"true": ..., "false": ...}``。"""
        return {
            edge.condition.value: edge.target
            for edge in self.outgoing(node_id)
            if edge.condition in (EdgeCondition.TRUE, EdgeCondition.FALSE)
        }

    def allowed_tools(self) -> list[str]:
        """本工作流声明的工具白名单（``tool`` 节点的 ``tool_name``，去重保序）。

        设计取舍：工具面在**编译期**由全图 ``tool`` 节点汇总，不做「按分支路径收敛」——
        白名单只做收敛不放权，简化实现且对正确性无影响。
        """
        names: list[str] = []
        for node in self.nodes:
            if node.type is not NodeType.TOOL:
                continue
            name = node.config_str("tool_name")
            if name and name not in names:
                names.append(name)
        return names


def parse_definition(raw: str, max_bytes: int = 262144) -> WorkflowDefinition:
    """解析图定义 JSON。

    Args:
        raw: 图定义正文（Java 编排域返回的 ``definition_json``）。
        max_bytes: 允许的最大字节数（与 Java ``lingxi.workflow.definition-max-bytes`` 对齐）。

    Raises:
        WorkflowDefinitionError: 正文为空、超限、非 JSON 对象或节点/边结构非法。
    """
    if not raw or not raw.strip():
        raise WorkflowDefinitionError("图定义为空")
    if len(raw.encode("utf-8")) > max_bytes:
        raise WorkflowDefinitionError(f"图定义超出大小上限（{max_bytes} 字节）")
    try:
        root = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise WorkflowDefinitionError("图定义不是合法 JSON") from exc
    if not isinstance(root, dict):
        raise WorkflowDefinitionError("图定义必须是 JSON 对象")

    return WorkflowDefinition(nodes=_parse_nodes(root), edges=_parse_edges(root))


def _parse_nodes(root: dict[str, Any]) -> list[WorkflowNode]:
    """解析节点数组；结构非法即抛错（运行期不猜修复）。"""
    raw_nodes = root.get("nodes")
    if not isinstance(raw_nodes, list) or not raw_nodes:
        raise WorkflowDefinitionError("图定义缺少 nodes 数组")
    nodes: list[WorkflowNode] = []
    for item in raw_nodes:
        if not isinstance(item, dict):
            raise WorkflowDefinitionError("节点必须是 JSON 对象")
        node_id = _text(item.get("id"))
        if not node_id:
            raise WorkflowDefinitionError("节点缺少 id")
        try:
            node_type = NodeType(str(item.get("type", "")).strip().lower())
        except ValueError as exc:
            raise WorkflowDefinitionError(f"节点 {node_id} 的类型非法") from exc
        config = item.get("config")
        if config is None:
            config = {}
        if not isinstance(config, dict):
            raise WorkflowDefinitionError(f"节点 {node_id} 的 config 必须是 JSON 对象")
        nodes.append(
            WorkflowNode(id=node_id, type=node_type, name=_text(item.get("name")) or node_id, config=config)
        )
    if len({node.id for node in nodes}) != len(nodes):
        raise WorkflowDefinitionError("存在重复的节点 id")
    return nodes


def _parse_edges(root: dict[str, Any]) -> list[WorkflowEdge]:
    """解析边数组；``condition`` 缺失时按 ``always`` 兜底（与 Java 一致）。"""
    raw_edges = root.get("edges")
    if not isinstance(raw_edges, list):
        raise WorkflowDefinitionError("图定义缺少 edges 数组")
    edges: list[WorkflowEdge] = []
    for item in raw_edges:
        if not isinstance(item, dict):
            raise WorkflowDefinitionError("连线必须是 JSON 对象")
        edge_id = _text(item.get("id"))
        source = _text(item.get("source"))
        target = _text(item.get("target"))
        if not edge_id or not source or not target:
            raise WorkflowDefinitionError("连线缺少 id/source/target")
        raw_condition = item.get("condition")
        condition = EdgeCondition.ALWAYS
        if raw_condition is not None and str(raw_condition).strip():
            try:
                condition = EdgeCondition(str(raw_condition).strip().lower())
            except ValueError as exc:
                raise WorkflowDefinitionError(f"连线 {edge_id} 的 condition 非法") from exc
        edges.append(WorkflowEdge(id=edge_id, source=source, target=target, condition=condition))
    return edges


def _text(value: Any) -> str:
    """取非空文本；非字符串或全空白返回空串。"""
    return str(value).strip() if value is not None else ""
