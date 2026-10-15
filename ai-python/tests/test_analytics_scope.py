"""分析上下文（contextvar）单测（M5-5.1，纯内存、不触网）。

覆盖三点：无 scope 时全部 no-op（离线评测/CLI 不受影响）、scope 生命周期与复位、
以及 Provider/网关侧写入的字段（provider/model/usage/首字延迟）语义正确。
"""

from __future__ import annotations

from app.analytics import (
    begin_llm_call,
    current_scope,
    mark_first_token,
    record_usage,
    reset_scope,
    set_scope,
)


def test_no_scope_by_default() -> None:
    """模块导入后默认无 scope（不产生任何埋点）。"""
    assert current_scope() is None


def test_helpers_are_noop_without_scope() -> None:
    """无 scope 时各助手函数均为 no-op，且不得写入任何上下文。"""
    begin_llm_call("deepseek", "deepseek-chat")
    record_usage({"prompt_tokens": 1, "completion_tokens": 1})
    mark_first_token()

    assert current_scope() is None


def test_set_and_reset_scope() -> None:
    """set_scope 建立上下文，reset_scope（finally）复位，避免污染后续消息。"""
    token = set_scope("tenant-1", "session-1", "msg-1", prompt_chars=40)
    scope = current_scope()
    assert scope is not None
    assert (scope.tenant_id, scope.session_id, scope.message_id) == ("tenant-1", "session-1", "msg-1")
    assert scope.prompt_chars == 40

    reset_scope(token)
    assert current_scope() is None


def test_llm_call_fields_are_recorded() -> None:
    """Provider 命中信息（provider/model）应写入当前 scope。"""
    token = set_scope("t", "s")
    try:
        begin_llm_call("deepseek", "deepseek-chat")
        begin_llm_call("qwen", None)  # model 缺省时归一为「未提供」
        scope = current_scope()
        assert scope is not None
        assert (scope.provider, scope.model) == ("qwen", "")
    finally:
        reset_scope(token)


def test_first_token_is_recorded_once() -> None:
    """首字延迟仅记一次：后续片段（含工具多轮）不得覆盖首个数值。"""
    token = set_scope("t", "s")
    try:
        scope = current_scope()
        assert scope is not None
        scope.first_token_ms = 123
        mark_first_token()
        assert scope.first_token_ms == 123

        scope.first_token_ms = 0  # 复位后首次标记应被记录（非负值）
        mark_first_token()
        assert scope.first_token_ms >= 0
    finally:
        reset_scope(token)


def test_take_usage_clears_to_avoid_double_counting() -> None:
    """取走用量后必须清空，确保下一轮 LLM 调用不会复用上一轮的数值。"""
    token = set_scope("t", "s")
    try:
        usage = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
        record_usage(usage)
        scope = current_scope()
        assert scope is not None
        assert scope.take_usage() == usage
        assert scope.take_usage() is None

        record_usage(None)  # 空用量不得覆盖已有值（此处已被清空，仍为 None）
        assert scope.take_usage() is None
    finally:
        reset_scope(token)


def test_record_usage_ignores_empty_payload() -> None:
    """提供方未下发 usage 时不写入（保持 None，交由发布侧按字符估算）。"""
    token = set_scope("t", "s")
    try:
        record_usage(None)
        record_usage({})
        scope = current_scope()
        assert scope is not None
        assert scope.usage is None
    finally:
        reset_scope(token)
