"""链路追踪上下文（M5-5.9）：用 ``contextvars`` 保存 trace_id / parent_span_id。

为什么用 contextvar：同一条链路会横跨 HTTP 中间件、Kafka 消费循环与后台任务，
三者分属不同协程。若靠函数签名逐层传参会污染整条调用链；而 contextvar 由
asyncio 在创建 Task 时**复制当前上下文**，因此天然按协程隔离——并发请求/消费
各自持有自己的 trace_id，不会互相串号（这正是本模块的核心正确性要求）。
"""

from __future__ import annotations

from contextvars import ContextVar, Token
from dataclasses import dataclass
from uuid import uuid4

# 默认空串表示「当前无链路」：无 trace_id 时下游不记 span、不注入消息字段，全部 no-op
_trace_id_var: ContextVar[str] = ContextVar("lingxi_trace_id", default="")
_parent_span_var: ContextVar[str] = ContextVar("lingxi_parent_span_id", default="")


def new_trace_id() -> str:
    """生成新的 trace_id（32 位 hex，无连字符，便于日志检索与跨端拼接）。"""
    return uuid4().hex


@dataclass(frozen=True, slots=True)
class TraceContext:
    """当前协程上下文的只读快照。"""

    trace_id: str
    parent_span_id: str


@dataclass(frozen=True, slots=True)
class TraceTokens:
    """``bind`` 返回的复位令牌（每个 contextvar 各持一个 token）。"""

    trace_token: Token
    parent_token: Token


def bind(trace_id: str, parent_span_id: str = "") -> TraceTokens:
    """绑定当前协程的 trace 上下文，返回复位令牌（**务必在 finally 中复位**）。

    未在 finally 复位会导致同一任务处理下一条消息时复用上一条 trace_id
    （与 ``analytics.scope`` 的 same-task 陷阱一致）。
    """
    return TraceTokens(
        _trace_id_var.set(trace_id or ""),
        _parent_span_var.set(parent_span_id or ""),
    )


def reset(tokens: TraceTokens) -> None:
    """复位上下文（``tokens`` 由 :func:`bind` 返回）。"""
    _trace_id_var.reset(tokens.trace_token)
    _parent_span_var.reset(tokens.parent_token)


def current() -> TraceContext:
    """读取当前上下文；未绑定时 trace_id 为空串（调用方据此走 no-op 分支）。"""
    return TraceContext(_trace_id_var.get(), _parent_span_var.get())
