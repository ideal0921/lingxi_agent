"""分析上下文（M5-5.1）：用 ``contextvars`` 在**不污染函数签名**的前提下收集 LLM 用量。

为什么用 contextvar：生产流式链路的调用栈很长
（``session_message → stream_workflow_or_default → 图节点 → gateway.stream → provider``），
逐层加参数会污染整条链；而用量采集点（Provider）与归属上下文（消费入口）相距很远。
contextvar 由消费入口设置、Provider 内旁路记录、网关收尾发布，**无 scope 时全部 no-op**
（评测、CLI、离线单测不受影响）。
"""

from __future__ import annotations

import time
from collections.abc import Mapping
from contextvars import ContextVar, Token
from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class AnalyticsScope:
    """一轮对话（一条用户消息）的分析上下文。

    同一 scope 覆盖该轮内的所有 LLM 调用（含工具多轮、含编排图内节点），
    因此 provider/model/usage 会随每次调用被覆盖，由 :func:`take_usage` 取走并清空。
    """

    tenant_id: str
    session_id: str
    message_id: str
    prompt_chars: int
    started_at: float = field(default_factory=time.perf_counter)
    provider: str = ""
    model: str = ""
    first_token_ms: int = 0
    usage: dict[str, Any] | None = None

    def begin_llm_call(self, provider: str, model: str) -> None:
        """记录当前调用命中的提供方与模型（由 Provider 在请求发出时调用）。"""
        self.provider = provider
        self.model = model

    def mark_first_token(self) -> None:
        """记录**本轮首个片段**的延迟（仅记一次；后续片段与工具轮次不覆盖）。"""
        if self.first_token_ms <= 0:
            self.first_token_ms = int((time.perf_counter() - self.started_at) * 1000)

    def record_usage(self, usage: Mapping[str, Any] | None) -> None:
        """记录提供方返回的真实 token 用量（流式 usage 帧 / 非流式 usage 字段）。"""
        if usage:
            self.usage = dict(usage)

    def take_usage(self) -> dict[str, Any] | None:
        """取走本次调用的用量并清空，避免下一轮复用上一轮的数值（防重复计数）。"""
        usage = self.usage
        self.usage = None
        return usage


_scope: ContextVar[AnalyticsScope | None] = ContextVar("lingxi_analytics_scope", default=None)


def set_scope(tenant_id: str, session_id: str, message_id: str = "", prompt_chars: int = 0) -> Token:
    """设置当前上下文的分析 scope，返回用于复位的 token（务必在 finally 中复位）。"""
    return _scope.set(AnalyticsScope(tenant_id=tenant_id, session_id=session_id,
                                     message_id=message_id, prompt_chars=prompt_chars))


def reset_scope(token: Token) -> None:
    """复位 scope（``Token`` 由 :func:`set_scope` 返回）。"""
    _scope.reset(token)


def current_scope() -> AnalyticsScope | None:
    """当前 scope；未设置时为 ``None``（调用方据此跳过埋点）。"""
    return _scope.get()


def begin_llm_call(provider: str, model: str | None) -> None:
    """Provider 侧调用：记录命中的提供方与模型；无 scope 时 no-op。"""
    scope = _scope.get()
    if scope is not None:
        scope.begin_llm_call(provider, model or "")


def mark_first_token() -> None:
    """网关侧调用：本轮首片段产出；无 scope 时 no-op。"""
    scope = _scope.get()
    if scope is not None:
        scope.mark_first_token()


def record_usage(usage: Mapping[str, Any] | None) -> None:
    """Provider 侧调用：记录真实 token 用量；无 scope 时 no-op。"""
    scope = _scope.get()
    if scope is not None:
        scope.record_usage(usage)
