"""分支表达式求值（M4-4.8）：白名单 AST 校验 + 显式比较，绝不使用 ``eval``/``compile``。

图定义是运营可在 Studio 里自由编辑的内容，若直接 ``eval`` 等于把代码执行权交给
运营输入——故本模块只支持**单一比较**形式 ``<变量> <运算符> <常量>``，并逐节点
校验 AST 只包含白名单类型；求值按运算符显式分派（**不生成代码、不执行代码**）。

支持的变量（运行期由上下文注入）：
- ``top_score``：首条命中分数（无命中为 0.0）
- ``hit_count``：命中条数
- ``has_hits``：是否有命中（布尔）
- ``query_length``：用户问题长度

求值失败（语法非法、未知变量、类型不可比）返回 ``None``；调用方据此走 ``false`` 出边
（``false`` 为约定兜底路径）并记录告警。
"""

from __future__ import annotations

import ast
import logging
import operator
from collections.abc import Callable
from typing import Any

logger = logging.getLogger(__name__)

# 允许引用的变量（其余一律拒绝，避免暴露内部状态）
ALLOWED_VARIABLES = frozenset({"top_score", "hit_count", "has_hits", "query_length"})

# 允许的比较运算符（AST 节点类型 → 求值函数）
_COMPARE_OPS: dict[type, Callable[[Any, Any], bool]] = {
    ast.Gt: operator.gt,
    ast.GtE: operator.ge,
    ast.Lt: operator.lt,
    ast.LtE: operator.le,
    ast.Eq: operator.eq,
    ast.NotEq: operator.ne,
}

# 允许的常量类型（禁止表达式里出现 ``...``/``None``/复数等）
_ALLOWED_CONSTANTS = (int, float, str, bool)


class ExpressionError(ValueError):
    """表达式非法（语法/结构/变量/类型不符合白名单）。"""


def evaluate(expression: str, variables: dict[str, Any], *, max_length: int = 200) -> bool | None:
    """求值分支表达式。

    Args:
        expression: 表达式文本（如 ``top_score >= 0.6``）。
        variables: 变量取值（仅 :data:`ALLOWED_VARIABLES` 中的键会被使用）。
        max_length: 表达式长度上限。

    Returns:
        比较结果；表达式非法或类型不可比时返回 ``None``（调用方走 ``false`` 兜底）。
    """
    if not expression or not expression.strip():
        return None
    if len(expression) > max_length:
        logger.warning("分支表达式超长被拒（%d 字符）：%.40s", len(expression), expression)
        return None
    try:
        tree = ast.parse(expression, mode="eval")
    except SyntaxError:
        logger.warning("分支表达式语法非法：%.60s", expression)
        return None
    try:
        left, op, right = _parse_comparison(tree)
        return bool(op(_value(left, variables), _value(right, variables)))
    except ExpressionError as exc:
        logger.warning("分支表达式被拒：%s（%.60s）", exc, expression)
        return None
    except TypeError:  # 类型不可比（如 str 与 float）
        logger.warning("分支表达式类型不可比较：%.60s", expression)
        return None


def _parse_comparison(tree: ast.Expression) -> tuple[ast.AST, Callable[[Any, Any], bool], ast.AST]:
    """校验 AST 白名单并取出（左值, 比较函数, 右值）。"""
    body = tree.body
    if not isinstance(body, ast.Compare):
        raise ExpressionError("仅支持单一比较表达式")
    if len(body.ops) != 1 or len(body.comparators) != 1:
        raise ExpressionError("不支持链式比较（如 a > b > c）")
    op_func = _COMPARE_OPS.get(type(body.ops[0]))
    if op_func is None:
        raise ExpressionError(f"比较运算符非法：{type(body.ops[0]).__name__}")
    left, right = body.left, body.comparators[0]
    _assert_allowed(left)
    _assert_allowed(right)
    return left, op_func, right


def _assert_allowed(node: ast.AST) -> None:
    """逐节点校验：仅允许 Name 与常量。"""
    if isinstance(node, ast.Name):
        if node.id not in ALLOWED_VARIABLES:
            raise ExpressionError(f"未知变量：{node.id}")
        return
    if isinstance(node, ast.Constant):
        if not isinstance(node.value, _ALLOWED_CONSTANTS):
            raise ExpressionError("常量类型不被支持")
        return
    raise ExpressionError(f"表达式仅支持变量与常量，出现非法节点：{type(node).__name__}")


def _value(node: ast.AST, variables: dict[str, Any]) -> Any:
    """取节点值；未知变量按 0 处理会掩盖错误，故此处仍抛错（由调用方降级）。"""
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Name):
        if node.id not in variables:
            raise ExpressionError(f"运行时缺少变量取值：{node.id}")
        return variables[node.id]
    raise ExpressionError("非法表达式节点")


def build_variables(*, scores: list[float], has_hits: bool, query_length: int) -> dict[str, Any]:
    """按检索结果构造表达式变量表（首条命中分数最高，故取 ``scores[0]``）。"""
    return {
        "top_score": float(scores[0]) if scores else 0.0,
        "hit_count": len(scores),
        "has_hits": bool(has_hits),
        "query_length": int(query_length),
    }
