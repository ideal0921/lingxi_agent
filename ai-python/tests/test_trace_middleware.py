"""HTTP 中间件单测（M5-5.9）：读 x-trace-id / 缺省生成；span 字段完整。

离线运行：用假 writer 捕获 span（monkeypatch 中间件模块的 ``get_writer``），
用真实 Starlette ``Request``/``Response`` 驱动中间件。
"""

from __future__ import annotations

from starlette.requests import Request
from starlette.responses import Response

from app.trace import middleware as mw


class _FakeWriter:
    def __init__(self) -> None:
        self.spans: list = []

    def record(self, span: object) -> None:
        self.spans.append(span)


def _install_writer(monkeypatch) -> _FakeWriter:
    writer = _FakeWriter()
    monkeypatch.setattr(mw, "get_writer", lambda: writer)
    return writer


def _request(headers: list[tuple[bytes, bytes]] | None = None, *, method: str = "GET") -> Request:
    scope = {
        "type": "http",
        "method": method,
        "path": "/api/v1/traces",
        "scheme": "http",
        "server": ("testserver", 80),
        "client": ("1.2.3.4", 5678),
        "query_string": b"",
        "headers": headers or [],
    }
    return Request(scope)


class TestResolveTraceId:
    def test_reads_incoming_header(self) -> None:
        assert mw.resolve_trace_id({"x-trace-id": "abc-123"}) == "abc-123"

    def test_generates_when_missing(self) -> None:
        generated = mw.resolve_trace_id({})
        assert len(generated) == 32

    def test_generates_when_blank(self) -> None:
        generated = mw.resolve_trace_id({"x-trace-id": "   "})
        assert len(generated) == 32


class TestMiddleware:
    async def test_reuses_incoming_trace_and_records_full_span(self, monkeypatch) -> None:
        writer = _install_writer(monkeypatch)

        async def call_next(_request: Request) -> Response:
            return Response(status_code=200)

        response = await mw.trace_middleware(
            _request([(b"x-trace-id", b"trace-abc")]), call_next
        )

        assert response.status_code == 200
        assert len(writer.spans) == 1
        span = writer.spans[0]
        assert span.trace_id == "trace-abc"
        assert span.service == "ai-python"
        assert span.component == "HTTP"
        assert span.entry == "/api/v1/traces"
        assert span.http_method == "GET"
        assert span.status_code == 200
        assert span.status == "OK"
        assert span.client_ip == "1.2.3.4"
        assert span.span_id and span.started_at > 0
        assert span.elapsed_ms >= 0

    async def test_generates_trace_when_header_absent(self, monkeypatch) -> None:
        writer = _install_writer(monkeypatch)

        async def call_next(_request: Request) -> Response:
            return Response(status_code=201)

        await mw.trace_middleware(_request(), call_next)
        assert len(writer.spans[0].trace_id) == 32

    async def test_error_status_marked_as_error(self, monkeypatch) -> None:
        writer = _install_writer(monkeypatch)

        async def call_next(_request: Request) -> Response:
            return Response(status_code=503)

        await mw.trace_middleware(_request(), call_next)
        assert writer.spans[0].status == "ERROR"

    async def test_exception_records_error_span_and_reraises(self, monkeypatch) -> None:
        writer = _install_writer(monkeypatch)

        async def call_next(_request: Request) -> Response:
            raise RuntimeError("boom")

        try:
            await mw.trace_middleware(_request(), call_next)
        except RuntimeError:
            pass
        assert len(writer.spans) == 1
        span = writer.spans[0]
        assert span.status == "ERROR"
        assert span.status_code == 500
        assert "boom" in span.error_msg
