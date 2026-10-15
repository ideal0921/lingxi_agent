"""LLM 用量埋点单测（M5-5.1）：发布契约、估算降级、失败兜底与 Provider 采集。

全部离线运行（不触网、不连 Kafka）：
- 发布链路通过替身拦截 :func:`_publish`，仅断言事件契约；
- Provider 采集用 ``httpx.MockTransport`` 拦截 SSE 响应。
"""

from __future__ import annotations

import asyncio
import inspect
import json

import httpx

import app.analytics.publisher as publisher_module
from app.analytics import (
    begin_llm_call,
    current_scope,
    flush_llm_usage,
    record_usage,
    reset_scope,
    set_scope,
)
from app.gateway.providers.openai_compat import OpenAICompatibleProvider

# 字符数 → token 的估算系数（与实现保持一致，便于断言具体数值）
_CHARS_PER_TOKEN = 4


def _sse(*lines: str) -> bytes:
    """按行拼装 SSE 响应体（``data:`` 行 + 结尾 [DONE]）。"""
    return "".join(f"{line}\n" for line in lines).encode()


def _data(payload: dict) -> str:
    """把一次增量包序列化为 SSE ``data:`` 行。"""
    return f"data: {json.dumps(payload, ensure_ascii=False)}"


def _sse_provider(
    responses: list[httpx.Response], captured: list[dict]
) -> OpenAICompatibleProvider:
    """构造注入 MockTransport 的 Provider：按顺序返回给定响应并记录请求体。"""

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content))
        return responses[min(len(captured) - 1, len(responses) - 1)]

    return OpenAICompatibleProvider(
        name="deepseek",
        api_key="test-key",
        base_url="https://mock.local/v1",
        model="deepseek-chat",
        transport=httpx.MockTransport(handler),
    )


class TestFlushContract:
    """发布侧契约：估算降级、字段完整性、取走清空。"""

    async def test_no_scope_produces_nothing(self, monkeypatch) -> None:
        """无 scope（离线评测/CLI）时不得产生任何事件，亦不得启动后台任务。"""
        captured: list[dict] = []

        async def _fake_publish(payload: dict) -> None:
            captured.append(payload)

        monkeypatch.setattr(publisher_module, "_publish", _fake_publish)

        flush_llm_usage(completion_chars=100, latency_ms=10)
        await asyncio.sleep(0)

        assert captured == []

    async def test_disabled_switch_produces_nothing(self, monkeypatch) -> None:
        """开关关闭时即便有 scope 也不产生事件。"""
        captured: list[dict] = []

        async def _fake_publish(payload: dict) -> None:
            captured.append(payload)

        monkeypatch.setattr(publisher_module, "_publish", _fake_publish)
        monkeypatch.setattr(publisher_module.settings, "analytics_enabled", False)

        token = set_scope("t", "s")
        try:
            flush_llm_usage(completion_chars=100, latency_ms=10)
            await asyncio.sleep(0)
        finally:
            reset_scope(token)

        assert captured == []

    async def test_real_usage_is_preferred(self, monkeypatch) -> None:
        """提供方下发真实 usage 时按其数值记录，且标记 estimated=false。"""
        captured = _capture(monkeypatch)
        token = set_scope("tenant-1", "session-1", "msg-1", prompt_chars=400)
        try:
            begin_llm_call("deepseek", "deepseek-chat")
            record_usage({"prompt_tokens": 11, "completion_tokens": 22, "total_tokens": 33})
            flush_llm_usage(completion_chars=999, latency_ms=42)
            await asyncio.sleep(0)
        finally:
            reset_scope(token)

        payload = captured[0]
        assert payload["type"] == "llm_usage"
        assert (payload["tenant_id"], payload["session_id"], payload["message_id"]) == (
            "tenant-1",
            "session-1",
            "msg-1",
        )
        assert (payload["provider"], payload["model"]) == ("deepseek", "deepseek-chat")
        assert (payload["prompt_tokens"], payload["completion_tokens"]) == (11, 22)
        assert payload["total_tokens"] == 33
        assert payload["estimated"] is False
        assert payload["latency_ms"] == 42
        assert len(payload["event_id"]) == 32  # 幂等主键（Java 侧 INSERT IGNORE 依赖唯一）

    async def test_missing_usage_falls_back_to_estimation(self, monkeypatch) -> None:
        """无真实 usage（提供方不支持）时按字符数估算并如实标记 estimated=true。"""
        captured = _capture(monkeypatch)
        token = set_scope("tenant-2", "session-2", prompt_chars=40)
        try:
            flush_llm_usage(completion_chars=80, latency_ms=5)
            await asyncio.sleep(0)
        finally:
            reset_scope(token)

        payload = captured[0]
        assert payload["prompt_tokens"] == 40 // _CHARS_PER_TOKEN
        assert payload["completion_tokens"] == 80 // _CHARS_PER_TOKEN
        assert payload["total_tokens"] == payload["prompt_tokens"] + payload["completion_tokens"]
        assert payload["estimated"] is True
        assert payload["provider"] == ""  # 未发生 LLM 调用时无归属提供方
        assert payload["first_token_ms"] == 0

    async def test_usage_is_consumed_once(self, monkeypatch) -> None:
        """一次真实 usage 只计一次：后续发布不得复用上一轮数值（防重复计数）。"""
        captured = _capture(monkeypatch)
        token = set_scope("t", "s", prompt_chars=0)
        try:
            record_usage({"prompt_tokens": 7, "completion_tokens": 3})
            flush_llm_usage(completion_chars=0, latency_ms=1)
            flush_llm_usage(completion_chars=0, latency_ms=1)
            await asyncio.sleep(0)
        finally:
            reset_scope(token)

        assert captured[0]["estimated"] is False
        assert captured[0]["prompt_tokens"] == 7
        assert captured[1]["estimated"] is True  # 第二次已无真实用量，退化为估算
        assert captured[1]["prompt_tokens"] == 0

    async def test_publish_failure_is_swallowed(self, monkeypatch) -> None:
        """投递失败（Kafka 不可用）必须被兜底吞掉，绝不冒泡到对话主链路。"""

        async def _boom() -> None:
            raise RuntimeError("kafka down")

        monkeypatch.setattr("app.runtime.get_producer", _boom)

        token = set_scope("t", "s")
        try:
            flush_llm_usage(completion_chars=1, latency_ms=1)
            await asyncio.sleep(0)
        finally:
            reset_scope(token)

        assert current_scope() is None  # 未抛异常即通过（失败仅告警）

    def test_spawn_background_without_loop_closes_coroutine(self) -> None:
        """同步调用（无事件循环）时退化为关闭协程，不得抛错或泄漏协程。"""

        async def _noop() -> None:
            return None

        coro = _noop()
        publisher_module._spawn_background(coro)

        assert inspect.getcoroutinestate(coro) == inspect.CORO_CLOSED


class TestProviderUsageCapture:
    """Provider 侧：stream_options 请求契约 + usage 帧采集 + 400 兼容降级。"""

    async def test_stream_chat_requests_usage_and_captures_frame(self) -> None:
        """流式请求应携带 stream_options.include_usage，并采集末帧 usage。"""
        captured: list[dict] = []
        provider = _sse_provider(
            [
                httpx.Response(
                    200,
                    content=_sse(
                        _data({"choices": [{"delta": {"content": "你"}}]}),
                        _data(
                            {
                                "choices": [],
                                "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10},
                            }
                        ),
                        "data: [DONE]",
                    ),
                    headers={"content-type": "text/event-stream"},
                )
            ],
            captured,
        )
        token = set_scope("t", "s")
        try:
            chunks = [chunk async for chunk in provider.stream_chat("hi")]
            usage = current_scope().usage
        finally:
            reset_scope(token)

        assert chunks == ["你"]  # usage 帧不产出文本（choices 为空）
        assert captured[0]["stream_options"] == {"include_usage": True}
        assert usage == {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}

    async def test_unsupported_stream_options_retries_once_without_it(self) -> None:
        """提供方不支持该字段（HTTP 400）时，去字段重试一次且后续不再携带。"""
        captured: list[dict] = []
        provider = _sse_provider(
            [
                httpx.Response(400, json={"error": "unsupported stream_options"}),
                httpx.Response(
                    200,
                    content=_sse(
                        _data({"choices": [{"delta": {"content": "ok"}}]}),
                        "data: [DONE]",
                    ),
                    headers={"content-type": "text/event-stream"},
                ),
            ],
            captured,
        )

        chunks = [chunk async for chunk in provider.stream_chat("hi")]

        assert chunks == ["ok"]
        assert len(captured) == 2, "应重试一次（共 2 次请求）"
        assert "stream_options" in captured[0]
        assert "stream_options" not in captured[1], "重试时不得再携带该字段"
        assert provider._stream_usage_unsupported is True, "已记忆该提供方不支持，避免每轮失败重试"

    async def test_stream_events_also_carries_usage_option(self) -> None:
        """工具链路（stream_events）同样要求 usage 帧。"""
        captured: list[dict] = []
        provider = _sse_provider(
            [
                httpx.Response(
                    200,
                    content=_sse(
                        _data({"choices": [{"delta": {"content": "hi"}}]}),
                        "data: [DONE]",
                    ),
                    headers={"content-type": "text/event-stream"},
                )
            ],
            captured,
        )
        token = set_scope("t", "s")
        try:
            events = [event async for event in provider.stream_events([{"role": "user", "content": "hi"}])]
        finally:
            reset_scope(token)

        assert [event.delta for event in events] == ["hi", ""]
        assert captured[0]["stream_options"] == {"include_usage": True}


def _capture(monkeypatch) -> list[dict]:
    """把真实投递替换为记录型替身（避免连 Kafka），返回可断言的记录列表。"""
    captured: list[dict] = []

    async def _fake_publish(payload: dict) -> None:
        captured.append(payload)

    monkeypatch.setattr(publisher_module, "_publish", _fake_publish)
    return captured
