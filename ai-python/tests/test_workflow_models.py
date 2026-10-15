"""工作流图定义解析单测（M4-4.8，离线，不触网）。

覆盖：合法图解析、结构非法路径（空/超限/非对象/缺节点边/未知类型/未知条件/重复 id/config 非对象）、
条件缺省兜底、入口唯一判定、分支目标解析、工具白名单汇总，以及节点配置取值语义。
"""

from __future__ import annotations

import pytest

from app.agent.workflow.models import (
    EdgeCondition,
    NodeType,
    WorkflowDefinitionError,
    parse_definition,
)

MAX_BYTES = 262144

VALID = """
{
  "nodes": [
    {"id": "n1", "type": "retrieve", "name": "知识检索", "config": {"kb_id": "kb-1", "top_k": 3}},
    {"id": "n2", "type": "branch", "name": "置信度分支", "config": {"expression": "top_score >= 0.6"}},
    {"id": "n3", "type": "llm", "name": "生成回答", "config": {"temperature": 0.7}},
    {"id": "n4", "type": "tool", "name": "查订单", "config": {"tool_name": "get_order"}},
    {"id": "n5", "type": "llm", "name": "兜底回答", "config": {}}
  ],
  "edges": [
    {"id": "e1", "source": "n1", "target": "n2"},
    {"id": "e2", "source": "n2", "target": "n3", "condition": "true"},
    {"id": "e3", "source": "n2", "target": "n4", "condition": "false"},
    {"id": "e4", "source": "n4", "target": "n5"}
  ]
}
"""


def test_parses_valid_definition() -> None:
    definition = parse_definition(VALID, MAX_BYTES)

    assert [node.id for node in definition.nodes] == ["n1", "n2", "n3", "n4", "n5"]
    assert definition.node("n1").type is NodeType.RETRIEVE
    assert definition.node("n2").type is NodeType.BRANCH
    assert definition.node("n4").type is NodeType.TOOL
    assert definition.node("n3").name == "生成回答"
    # 缺省 condition 按 always 兜底（与 Java 一致）
    assert definition.outgoing("n1")[0].condition is EdgeCondition.ALWAYS
    assert definition.outgoing("n2")[1].condition is EdgeCondition.FALSE


def test_entry_and_branch_targets() -> None:
    definition = parse_definition(VALID, MAX_BYTES)

    assert definition.entry_id() == "n1"
    assert definition.branch_targets("n2") == {"true": "n3", "false": "n4"}


def test_entry_is_none_when_multiple_candidates() -> None:
    raw = """
    {"nodes": [{"id": "a", "type": "llm"}, {"id": "b", "type": "llm"}], "edges": []}
    """
    assert parse_definition(raw, MAX_BYTES).entry_id() is None


def test_allowed_tools_deduplicates_and_keeps_order() -> None:
    raw = """
    {"nodes": [
        {"id": "t1", "type": "tool", "config": {"tool_name": "get_order"}},
        {"id": "l1", "type": "llm", "config": {"tool_name": "不该被收集"}},
        {"id": "t2", "type": "tool", "config": {"tool_name": "get_logistics"}},
        {"id": "t3", "type": "tool", "config": {"tool_name": "get_order"}},
        {"id": "t4", "type": "tool", "config": {}}
     ], "edges": []}
    """
    assert parse_definition(raw, MAX_BYTES).allowed_tools() == ["get_order", "get_logistics"]


@pytest.mark.parametrize(
    ("raw", "hint"),
    [
        ("", "为空"),
        ("   ", "为空"),
        ("[]", "JSON 对象"),
        ("{not json", "合法 JSON"),
        ('{"edges": []}', "nodes"),
        ('{"nodes": []}', "nodes"),
        ('{"nodes": [{"type": "llm"}], "edges": []}', "id"),
        ('{"nodes": [{"id": "n1", "type": "unknown"}], "edges": []}', "类型非法"),
        ('{"nodes": [{"id": "n1", "type": "llm", "config": "x"}], "edges": []}', "config"),
        (
            '{"nodes": [{"id": "n1", "type": "llm"}, {"id": "n1", "type": "llm"}], "edges": []}',
            "重复",
        ),
        ('{"nodes": [{"id": "n1", "type": "llm"}]}', "edges"),
        ('{"nodes": [{"id": "n1", "type": "llm"}], "edges": [{"id": "e", "source": "n1"}]}', "id/source/target"),
        (
            '{"nodes": [{"id": "n1", "type": "llm"}], "edges": [{"id": "e", "source": "n1", "target": "n1", "condition": "maybe"}]}',
            "condition",
        ),
    ],
)
def test_rejects_illegal_structures(raw: str, hint: str) -> None:
    with pytest.raises(WorkflowDefinitionError) as excinfo:
        parse_definition(raw, MAX_BYTES)
    assert hint in str(excinfo.value)


def test_rejects_oversized_definition() -> None:
    raw = '{"nodes": [{"id": "n1", "type": "llm", "name": "' + "x" * 200 + '"}], "edges": []}'
    with pytest.raises(WorkflowDefinitionError) as excinfo:
        parse_definition(raw, 32)
    assert "上限" in str(excinfo.value)


def test_node_config_accessors() -> None:
    node = parse_definition(
        '{"nodes": [{"id": "n1", "type": "retrieve", '
        '"config": {"kb_id": "  ", "top_k": "3", "candidate_k": 10, "flag": true}}], "edges": []}',
        MAX_BYTES,
    ).node("n1")

    assert node.config_str("kb_id") is None  # 全空白视为未配置
    assert node.config_str("missing") is None
    assert node.config_int("top_k") == 3  # 数字字符串可解析
    assert node.config_int("candidate_k") == 10
    assert node.config_int("missing", default=7) == 7
    assert node.config_int("flag") == 0  # bool 不作为整数（避免 True 被当成 1）
