"""分析埋点（M5-5.1）：会话漏斗 / LLM 用量 / 满意度事件的采集与发布。

- :mod:`scope`：contextvar 形式的分析上下文（无 scope 时全部 no-op）；
- :mod:`publisher`：LLM 用量事件的后台发布（三级降级：真实 usage → 兼容重试 → 字符估算）。
"""

from app.analytics.publisher import flush_llm_usage
from app.analytics.scope import (
    AnalyticsScope,
    begin_llm_call,
    current_scope,
    mark_first_token,
    record_usage,
    reset_scope,
    set_scope,
)

__all__ = [
    "AnalyticsScope",
    "begin_llm_call",
    "current_scope",
    "flush_llm_usage",
    "mark_first_token",
    "record_usage",
    "reset_scope",
    "set_scope",
]
