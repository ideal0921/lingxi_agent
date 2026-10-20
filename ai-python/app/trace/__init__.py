"""API 接口链路追踪（M5-5.9）：context / span / writer / middleware 的统一出口。

对外便捷函数供 HTTP 中间件、Kafka 消费循环与生产端复用。
"""

from app.trace.context import TraceContext, bind, current, new_trace_id, reset
from app.trace.middleware import TRACE_HEADER, build_http_span, trace_middleware
from app.trace.span import Span, new_span_id, now_ms
from app.trace.writer import TraceWriter, get_writer, set_writer

__all__ = [
    "TRACE_HEADER",
    "Span",
    "TraceContext",
    "TraceWriter",
    "bind",
    "build_http_span",
    "current",
    "get_writer",
    "new_span_id",
    "new_trace_id",
    "now_ms",
    "reset",
    "set_writer",
    "trace_middleware",
]
