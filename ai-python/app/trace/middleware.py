"""FastAPI HTTP 中间件（M5-5.9）：读入站 ``x-trace-id``（无则生成）→ 绑定上下文 → 记 HTTP span。

- **先绑定再调用下游**：下游在同一协程上下文内的日志/子 span 可拿到 trace_id；
- span 在**响应返回后**才记录（需要状态码与耗时），经 :class:`~app.trace.writer.TraceWriter`
  旁路落库，**不进入请求关键路径**；
- HTTP 状态码 >=400 视为 ``ERROR``（涵盖鉴权拒绝等失败），否则 ``OK``；
- ``parent_span_id`` 留空：本服务的 HTTP 段即该链路在本进程内的起点。
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from typing import Any

from starlette.requests import Request
from starlette.responses import Response

from app.trace.context import bind, new_trace_id, reset
from app.trace.span import Span, new_span_id, now_ms
from app.trace.writer import get_writer

logger = logging.getLogger(__name__)

# 与 Java（metadata）/Go（header）统一的链路头 key（HTTP 场景大小写不敏感）
TRACE_HEADER = "x-trace-id"

# 组件类型常量
COMPONENT_HTTP = "HTTP"


def resolve_trace_id(headers: Any) -> str:
    """从入站请求头取 trace_id；缺失或空白则生成新的（便于跨端联调复用已有链路）。"""
    value = ""
    if headers is not None:
        value = str(headers.get(TRACE_HEADER) or "").strip()
    return value or new_trace_id()


def build_http_span(
    *,
    trace_id: str,
    method: str,
    path: str,
    status_code: int,
    elapsed_ms: int,
    client_ip: str = "",
    tenant_id: str = "",
    user_id: str = "",
    role: str = "",
    parent_span_id: str = "",
    error_msg: str = "",
) -> Span:
    """构造 HTTP span（字段与表 ``lingxi_api_trace`` 对齐）。"""
    return Span(
        span_id=new_span_id(),
        trace_id=trace_id,
        parent_span_id=parent_span_id,
        component=COMPONENT_HTTP,
        entry=path,
        http_method=method,
        status="ERROR" if status_code >= 400 else "OK",
        status_code=status_code,
        tenant_id=tenant_id,
        user_id=user_id,
        role=role,
        client_ip=client_ip,
        elapsed_ms=elapsed_ms,
        error_msg=error_msg,
        started_at=now_ms(),
    )


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else ""


async def trace_middleware(
    request: Request, call_next: Callable[[Request], Awaitable[Response]]
) -> Response:
    """记录一次 HTTP 调用：绑定 trace_id → 调用下游 → 记 span（含异常路径）。"""
    trace_id = resolve_trace_id(request.headers)
    # 绑定到当前协程上下文；下游（同任务）可见；本函数退出前复位，避免串号
    tokens = bind(trace_id)
    span_id = new_span_id()
    started = now_ms()
    method = request.method
    path = request.url.path
    client_ip = _client_ip(request)
    try:
        response = await call_next(request)
    except Exception as exc:
        _record(
            trace_id=trace_id,
            span_id=span_id,
            method=method,
            path=path,
            client_ip=client_ip,
            status_code=500,
            started=started,
            error_msg=str(exc),
        )
        raise
    finally:
        reset(tokens)
    _record(
        trace_id=trace_id,
        span_id=span_id,
        method=method,
        path=path,
        client_ip=client_ip,
        status_code=response.status_code,
        started=started,
    )
    return response


def _record(
    *,
    trace_id: str,
    span_id: str,
    method: str,
    path: str,
    client_ip: str,
    status_code: int,
    started: int,
    error_msg: str = "",
) -> None:
    """落一条 HTTP span（旁路；写失败不影响请求）。"""
    span = build_http_span(
        trace_id=trace_id,
        method=method,
        path=path,
        status_code=status_code,
        elapsed_ms=now_ms() - started,
        client_ip=client_ip,
        error_msg=error_msg,
    )
    span.span_id = span_id  # 复用进入时生成的 span_id（与 elapsed 起点一致）
    get_writer().record(span)
