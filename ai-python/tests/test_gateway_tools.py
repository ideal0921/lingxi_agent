"""原生 Function Calling（tools / tool_calls）单测（离线，不触网）。

覆盖：OpenAI 兼容 Provider 的 ``stream_events`` SSE 解析（纯文本 / 工具调用分片累积 /
多工具并行 / 非法行容错 / 流结束补齐）、Router 事件流降级与「已产出不重复降级」、
以及不支持 tools 的 Provider 退回文本流的兜底行为。云端通道统一用
``httpx.MockTransport`` 拦截。
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Iterable

import httpx
import pytest

from app.gateway.providers.base import BaseProvider, ProviderStreamEvent, ToolCallRequest
from app.gateway.providers.openai_compat import OpenAICompatibleProvider
from app.gateway.router import LLMGateway

_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "查询天气",
            "parameters": {"type": "object", "properties": {"city": {"type": "string"}}},
        },
    }
]


def _data(payload: dict) -> str:
    """把一次增量包序列化为 SSE ``data:`` 行。"""
    return f"data: {json.dumps(payload, ensure_ascii=False)}"


def _sse(*lines: str) -> bytes:
    """按行拼装 SSE 响应体（空串表示事件分隔空行）。"""
    return "".join(f"{line}\n" for line in lines).encode()


def _provider(body: bytes, captured: list[dict] | None = None) -> OpenAICompatibleProvider:
    """构造注入 MockTransport 的 OpenAI 兼容 Provider（记录请求体，供契约断言）。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(json.loads(request.content))
        return httpx.Response(200, content=body, headers={"content-type": "text/event-stream"})

    return OpenAICompatibleProvider(
        name="mock",
        api_key="test-key",
        base_url="https://mock.local/v1",
        model="mock-model",
        transport=httpx.MockTransport(handler),
    )


async def _collect(events: AsyncIterator[ProviderStreamEvent]) -> list[ProviderStreamEvent]:
    return [event async for event in events]


class TestStreamEventsText:
    async def test_text_fragments_then_terminal(self) -> None:
        """纯文本：多个 content 分片 → delta 序列 + 终止事件。"""
        captured: list[dict] = []
        provider = _provider(
            _sse(
                _data({"choices": [{"delta": {"content": "你"}}]}),
                _data({"choices": [{"delta": {"content": "好"}}]}),
                "data: [DONE]",
            ),
            captured,
        )

        events = await _collect(provider.stream_events([{"role": "user", "content": "hi"}]))

        assert [event.delta for event in events] == ["你", "好", ""]
        assert all(event.tool_call is None for event in events)
        assert events[-1] == ProviderStreamEvent()  # 终止信号：空 delta、无 tool_call
        # 请求契约：OpenAI 消息透传、stream=True、tools=None 时不带 tools 字段
        assert captured[0]["messages"] == [{"role": "user", "content": "hi"}]
        assert captured[0]["stream"] is True
        assert "tools" not in captured[0]


class TestStreamEventsToolCalls:
    async def test_fragmented_tool_call_is_accumulated(self) -> None:
        """工具调用：name 在首片，arguments 分片下发 → 仅产出一个完整 tool_call。"""
        captured: list[dict] = []
        provider = _provider(
            _sse(
                _data(
                    {
                        "choices": [
                            {
                                "delta": {
                                    "tool_calls": [
                                        {
                                            "index": 0,
                                            "id": "call_1",
                                            "function": {"name": "get_weather"},
                                        }
                                    ]
                                }
                            }
                        ]
                    }
                ),
                _data(
                    {
                        "choices": [
                            {"delta": {"tool_calls": [{"index": 0, "function": {"arguments": '{"city":'}}]}}
                        ]
                    }
                ),
                _data(
                    {
                        "choices": [
                            {"delta": {"tool_calls": [{"index": 0, "function": {"arguments": '"北京"}'}}]}}
                        ]
                    }
                ),
                _data({"choices": [{"delta": {}, "finish_reason": "tool_calls"}]}),
                "data: [DONE]",
            ),
            captured,
        )

        events = await _collect(
            provider.stream_events([{"role": "user", "content": "北京天气"}], tools=_TOOLS)
        )

        assert len(events) == 2  # 一个 tool_call + 一个终止事件
        call = events[0].tool_call
        assert call == ToolCallRequest(id="call_1", name="get_weather", arguments='{"city":"北京"}')
        assert events[1] == ProviderStreamEvent()
        assert captured[0]["tools"] == _TOOLS  # tools 非空时应随请求下发

    async def test_two_parallel_tool_calls_interleaved(self) -> None:
        """多工具并行：index 0/1 交叉分片 → 各自按 index 累积正确。"""
        provider = _provider(
            _sse(
                _data(
                    {
                        "choices": [
                            {
                                "delta": {
                                    "tool_calls": [
                                        {"index": 0, "id": "c0", "function": {"name": "alpha"}},
                                        {"index": 1, "id": "c1", "function": {"name": "beta"}},
                                    ]
                                }
                            }
                        ]
                    }
                ),
                _data(
                    {
                        "choices": [
                            {"delta": {"tool_calls": [{"index": 1, "function": {"arguments": '{"y":2}'}}]}}
                        ]
                    }
                ),
                _data(
                    {
                        "choices": [
                            {"delta": {"tool_calls": [{"index": 0, "function": {"arguments": '{"x":1}'}}]}}
                        ]
                    }
                ),
                _data({"choices": [{"delta": {}, "finish_reason": "tool_calls"}]}),
                "data: [DONE]",
            )
        )

        events = await _collect(provider.stream_events([{"role": "user", "content": "go"}], tools=_TOOLS))

        calls = [event.tool_call for event in events if event.tool_call is not None]
        assert calls == [
            ToolCallRequest(id="c0", name="alpha", arguments='{"x":1}'),
            ToolCallRequest(id="c1", name="beta", arguments='{"y":2}'),
        ]

    async def test_tool_call_flushed_on_stream_end_without_finish_reason(self) -> None:
        """流自然结束（无 finish_reason / 无 [DONE]）时也应补齐工具调用。"""
        provider = _provider(
            _sse(
                _data(
                    {
                        "choices": [
                            {
                                "delta": {
                                    "tool_calls": [
                                        {"index": 0, "id": "c9", "function": {"name": "now"}}
                                    ]
                                }
                            }
                        ]
                    }
                ),
                _data({"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{}"}}]}}]})
            )
        )

        events = await _collect(provider.stream_events([{"role": "user", "content": "go"}], tools=_TOOLS))

        assert events[0].tool_call == ToolCallRequest(id="c9", name="now", arguments="{}")
        assert events[-1] == ProviderStreamEvent()


class TestStreamEventsTolerance:
    async def test_invalid_and_blank_lines_are_ignored(self) -> None:
        """非法 JSON / 空行 / 注释行不中断流，仅跳过。"""
        provider = _provider(
            _sse(
                "data: not-json",
                "",
                ": keep-alive",
                _data({"choices": [{"delta": {"content": "片段"}}]}),
                "data: {bad json",
                "data: [DONE]",
            )
        )

        events = await _collect(provider.stream_events([{"role": "user", "content": "hi"}]))

        assert [event.delta for event in events] == ["片段", ""]


class _EventsProvider(BaseProvider):
    """可控行为的假 Provider：验证 Router 事件流降级与首字超时保护。"""

    def __init__(
        self,
        name: str,
        *,
        events: Iterable[ProviderStreamEvent] = (),
        error: Exception | None = None,
        fail_after: int | None = None,
        delay_before_first: float = 0.0,
    ) -> None:
        self.name = name
        self._events = list(events)
        self._error = error
        self._fail_after = fail_after
        self._delay_before_first = delay_before_first
        self.calls = 0

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        return ""

    async def stream_events(
        self, messages: list[dict], tools: list[dict] | None = None, model: str | None = None
    ) -> AsyncIterator[ProviderStreamEvent]:
        self.calls += 1
        if self._delay_before_first:
            await asyncio.sleep(self._delay_before_first)
        if self._error is not None and not self._events:
            raise self._error
        for position, event in enumerate(self._events):
            if self._fail_after is not None and position >= self._fail_after:
                raise self._error or RuntimeError("事件流中断")
            yield event


class TestRouterStreamEvents:
    async def test_fallback_before_first_event(self) -> None:
        """主 Provider 首字前失败 → 切到备用 Provider 并正常产出事件。"""
        primary = _EventsProvider("primary", error=RuntimeError("不可用"))
        backup = _EventsProvider(
            "backup", events=[ProviderStreamEvent(delta="ok"), ProviderStreamEvent()]
        )
        gateway = LLMGateway([primary, backup], max_retries=0)

        events = await _collect(gateway.stream_events([{"role": "user", "content": "hi"}]))

        assert [event.delta for event in events] == ["ok", ""]
        assert primary.calls == 1
        assert backup.calls == 1

    async def test_first_token_timeout_switches_provider(self) -> None:
        slow = _EventsProvider(
            "slow", events=[ProviderStreamEvent(delta="太慢")], delay_before_first=0.5
        )
        fast = _EventsProvider("fast", events=[ProviderStreamEvent(delta="快速"), ProviderStreamEvent()])
        gateway = LLMGateway([slow, fast], max_retries=0, first_token_timeout=0.05)

        events = await _collect(gateway.stream_events([{"role": "user", "content": "hi"}]))

        assert [event.delta for event in events] == ["快速", ""]

    async def test_no_duplicate_after_partial_output(self) -> None:
        """已产出事件后中断：不得切换 Provider 重放，避免半截内容重复。"""
        primary = _EventsProvider(
            "primary",
            events=[ProviderStreamEvent(delta="一"), ProviderStreamEvent(delta="二")],
            error=RuntimeError("中断"),
            fail_after=1,
        )
        backup = _EventsProvider("backup", events=[ProviderStreamEvent(delta="重放")])
        gateway = LLMGateway([primary, backup], max_retries=1)

        events = await _collect(gateway.stream_events([{"role": "user", "content": "hi"}]))

        assert [event.delta for event in events] == ["一"]
        assert backup.calls == 0

    async def test_tool_call_counts_as_output(self) -> None:
        """tool_call 事件同样视为已产出，中断后不得降级。"""
        primary = _EventsProvider(
            "primary",
            events=[ProviderStreamEvent(tool_call=ToolCallRequest(id="c", name="t", arguments="{}"))],
            error=RuntimeError("中断"),
            fail_after=1,
        )
        backup = _EventsProvider("backup", events=[ProviderStreamEvent(delta="重放")])
        gateway = LLMGateway([primary, backup], max_retries=0)

        events = await _collect(gateway.stream_events([{"role": "user", "content": "hi"}]))

        assert [event.tool_call for event in events if event.tool_call] == [
            ToolCallRequest(id="c", name="t", arguments="{}")
        ]
        assert backup.calls == 0

    async def test_all_providers_fail_raises(self) -> None:
        gateway = LLMGateway([_EventsProvider("p1", error=RuntimeError("x"))], max_retries=0)

        with pytest.raises(RuntimeError, match="全部模型流式调用失败"):
            await _collect(gateway.stream_events([{"role": "user", "content": "hi"}]))


class _LegacyProvider(BaseProvider):
    """仅实现文本流（stream_chat）的 Provider：验证 stream_events 默认兜底。"""

    name = "legacy"

    def __init__(self) -> None:
        self.prompts: list[str] = []

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        return "甲乙"

    async def stream_chat(
        self,
        prompt: str,
        model: str | None = None,
        timeout: float = 30.0,
        first_token_timeout: float = 10.0,
    ) -> AsyncIterator[str]:
        self.prompts.append(prompt)
        yield "甲"
        yield "乙"


class TestDefaultStreamEventsFallback:
    async def test_tools_none_falls_back_to_text_stream(self) -> None:
        """不支持 tools 的 Provider：忽略 tools，拼装消息后复用 stream_chat（文本事件）。"""
        provider = _LegacyProvider()

        events = await _collect(
            provider.stream_events(
                [{"role": "system", "content": "你是助手"}, {"role": "user", "content": "问题"}],
                tools=None,
            )
        )

        assert [event.delta for event in events] == ["甲", "乙", ""]
        assert all(event.tool_call is None for event in events)
        # messages 已降级拼装为提示词并透传给 stream_chat
        assert provider.prompts == ["system: 你是助手\nuser: 问题"]

    async def test_tools_ignored_but_still_streams_text(self) -> None:
        """即便传入 tools，无原生能力时也应忽略并退回文本流（不报错）。"""
        provider = _LegacyProvider()

        events = await _collect(provider.stream_events([{"role": "user", "content": "问题"}], tools=_TOOLS))

        assert [event.delta for event in events] == ["甲", "乙", ""]
