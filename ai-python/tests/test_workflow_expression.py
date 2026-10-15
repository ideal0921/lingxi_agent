"""分支表达式白名单求值单测（M4-4.8）：安全边界优先。

图定义是运营可在 Studio 里编辑的内容，若走 ``eval`` 等于把代码执行权交出去。
本测试逐一验证「只允许单一比较」的白名单边界：函数调用、属性访问、下标、算术、
链式比较、未知变量、超长表达式、非法字面量一律拒绝并返回 ``None``（上层走 false 兜底）。
"""

from __future__ import annotations

import pytest

from app.agent.workflow.expression import ALLOWED_VARIABLES, build_variables, evaluate

VARIABLES = {
    "top_score": 0.83,
    "hit_count": 3,
    "has_hits": True,
    "query_length": 12,
}


@pytest.mark.parametrize(
    ("expression", "expected"),
    [
        ("top_score >= 0.6", True),
        ("top_score > 0.9", False),
        ("top_score <= 0.83", True),
        ("top_score < 0.1", False),
        ("hit_count == 3", True),
        ("hit_count != 3", False),
        ("has_hits == True", True),  # 表达式文本由运营书写，不是 Python 代码
        ("has_hits == False", False),
        ("query_length >= 10", True),
    ],
)
def test_evaluates_whitelisted_comparisons(expression: str, expected: bool) -> None:
    assert evaluate(expression, VARIABLES) is expected


@pytest.mark.parametrize(
    "expression",
    [
        "__import__('os').system('ls')",  # 函数调用
        "top_score.__class__",  # 属性访问
        "top_score < 1 and hit_count > 0",  # 布尔运算（多比较）
        "top_score >= 0.5 > hit_count",  # 链式比较
        "top_score + 1 >= 2",  # 算术
        "unknown_var >= 1",  # 未知变量
        "top_score >= None",  # 非法字面量
        "top_score >= [1, 2]",  # 列表字面量
        "top_score >= -1",  # 负数常量含 UnaryOp 节点 → 拒绝
    ],
)
def test_rejects_non_whitelisted_expressions(expression: str) -> None:
    assert evaluate(expression, VARIABLES) is None


def test_parentheses_are_only_syntax_sugar() -> None:
    # 括号不引入新 AST 节点，仍是单一比较 → 合法
    assert evaluate("(top_score) >= 0.5", VARIABLES) is True


def test_rejects_syntax_error_and_empty() -> None:
    assert evaluate("top_score >=", VARIABLES) is None
    assert evaluate("", VARIABLES) is None
    assert evaluate("   ", VARIABLES) is None


def test_rejects_oversized_expression() -> None:
    long_expression = "top_score >= " + "0" * 300
    assert evaluate(long_expression, VARIABLES, max_length=50) is None


def test_returns_none_on_type_mismatch() -> None:
    # 字符串与浮点不可比较 → 视为求值失败（走 false 兜底），而非抛错中断
    assert evaluate("top_score >= 'abc'", VARIABLES) is None


def test_returns_none_when_variable_missing_at_runtime() -> None:
    assert evaluate("top_score >= 0.5", {"hit_count": 1}) is None


def test_build_variables_from_retrieval_results() -> None:
    variables = build_variables(scores=[0.91, 0.42], has_hits=True, query_length=7)
    assert variables == {
        "top_score": 0.91,
        "hit_count": 2,
        "has_hits": True,
        "query_length": 7,
    }
    # 无命中：top_score 归 0、has_hits 为 False（分支可据此走兜底）
    empty = build_variables(scores=[], has_hits=False, query_length=3)
    assert empty["top_score"] == 0.0
    assert empty["has_hits"] is False


def test_allowed_variables_are_documented_set() -> None:
    assert ALLOWED_VARIABLES == {"top_score", "hit_count", "has_hits", "query_length"}
