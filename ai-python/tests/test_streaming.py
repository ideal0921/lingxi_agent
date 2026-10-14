"""流式链路单测（M3-3.8，离线运行，不连 Kafka / 不触网）。

覆盖：SSE 行解析、网关流式降级与首字超时保护、Agent 流式事件序列、
会话消息消费编排（片段下发 + 终帧 + 最终回答回写）与失败兜底。
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections.abc import AsyncIterator, Sequence

import pytest

from app.agent.graph import stream_answer
from app.agent.state import ChatMessage, StreamKind
from app.config import Settings
from app.consumers.session_message import (
    build_chunk_payload,
    build_reply_payload,
    handle_session_message,
)
from app.gateway.providers.base import BaseProvider
from app.gateway.providers.deepseek import _STREAM_END, DeepSeekProvider
from app.gateway.router import LLMGateway


class _FakeProvider(BaseProvider):
    """可控行为的假 Provider：用于验证降级链与首字超时保护。"""

    def __init__(
        self,
        name: str,
        *,
        deltas: Sequence[str] = (),
        chat_error: Exception | None = None,
        stream_error: Exception | None = None,
        delay_before_first: float = 0.0,
        fail_after_first: bool = False,
    ) -> None:
        self.name = name
        self._deltas = list(deltas)
        self._chat_error = chat_error
        self._stream_error = stream_error
        self._delay_before_first = delay_before_first
        self._fail_after_first = fail_after_first
        self.stream_calls = 0

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        if self._chat_error is not None:
            raise self._chat_error
        return "".join(self._deltas)

    async def stream_chat(
        self,
        prompt: str,
        model: str | None = None,
        timeout: float = 30.0,
        first_token_timeout: float = 10.0,
    ) -> AsyncIterator[str]:
        self.stream_calls += 1
        if self._delay_before_first:
            await asyncio.sleep(self._delay_before_first)
        if self._stream_error is not None and not self._deltas:
            raise self._stream_error
        for position, delta in enumerate(self._deltas):
            if self._fail_after_first and position == 1:
                raise self._stream_error or RuntimeError("流中断")
            yield delta


class _FakeGateway:
    """实现 generate + stream 的假网关。"""

    def __init__(self, deltas: Sequence[str] = ("片段一", "片段二")) -> None:
        self._deltas = list(deltas)

    async def generate(self, prompt: str, model: str | None = None) -> str:
        return "".join(self._deltas)

    async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
        for delta in self._deltas:
            yield delta


class _FakeBrokenStreamGateway:
    """先产出片段、随后抛错的假网关（验证已产出后不重复降级）。"""

    async def generate(self, prompt: str, model: str | None = None) -> str:
        return "x"

    async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
        yield "首片段"
        raise RuntimeError("上游中断")


class _RecordingProducer:
    """记录发布内容的生产者替身。"""

    def __init__(self) -> None:
        self.published: list[dict] = []

    async def publish(self, topic: str, payload: dict, key: str | None = None) -> None:
        self.published.append({"topic": topic, "payload": payload, "key": key})

    def by_topic(self, topic: str) -> list[dict]:
        return [item["payload"] for item in self.published if item["topic"] == topic]


def _config(**overrides) -> Settings:
    base = {
        "kafka_topic_ai_chunk": "chunk-topic",
        "kafka_topic_ai_reply": "reply-topic",
        "agent_default_tenant_id": "tenant-default",
        "agent_default_kb_id": "kb-default",
    }
    base.update(overrides)
    return Settings(**base)


class TestSseParsing:
    def test_extracts_content_delta(self) -> None:
        line = 'data: {"choices":[{"delta":{"content":"你好"}}]}'
        assert DeepSeekProvider._parse_sse_line(line) == "你好"

    def test_done_marker(self) -> None:
        assert DeepSeekProvider._parse_sse_line("data: [DONE]") is _STREAM_END

    def test_ignores_non_data_and_empty_delta(self) -> None:
        assert DeepSeekProvider._parse_sse_line(": keep-alive") is None
        assert DeepSeekProvider._parse_sse_line('data: {"choices":[]}') is None
        assert DeepSeekProvider._parse_sse_line('data: {"choices":[{"delta":{}}]}') is None

    def test_ignores_invalid_json(self) -> None:
        assert DeepSeekProvider._parse_sse_line("data: {not-json") is None


async def _collect(stream: AsyncIterator[str]) -> list[str]:
    return [item async for item in stream]


class TestGatewayStream:
    async def test_happy_path_preserves_order(self) -> None:
        gateway = LLMGateway([_FakeProvider("p1", deltas=["a", "b", "c"])], max_retries=0)

        assert await _collect(gateway.stream("prompt")) == ["a", "b", "c"]

    async def test_fallback_when_first_provider_fails_before_token(self) -> None:
        primary = _FakeProvider("primary", stream_error=RuntimeError("不可用"))
        backup = _FakeProvider("backup", deltas=["ok"])
        gateway = LLMGateway([primary, backup], max_retries=0)

        assert await _collect(gateway.stream("prompt")) == ["ok"]
        assert primary.stream_calls == 1
        assert backup.stream_calls == 1

    async def test_first_token_timeout_switches_provider(self) -> None:
        slow = _FakeProvider("slow", deltas=["太慢"], delay_before_first=0.5)
        fast = _FakeProvider("fast", deltas=["快速"])
        gateway = LLMGateway([slow, fast], max_retries=0, first_token_timeout=0.05)

        assert await _collect(gateway.stream("prompt")) == ["快速"]

    async def test_no_duplicate_after_partial_output(self) -> None:
        """已产出片段后中断：不得切换 Provider 重放，避免半截内容重复。"""
        primary = _FakeProvider("primary", deltas=["一", "二"], fail_after_first=True)
        backup = _FakeProvider("backup", deltas=["重放"])
        gateway = LLMGateway([primary, backup], max_retries=1)

        assert await _collect(gateway.stream("prompt")) == ["一"]
        assert backup.stream_calls == 0

    async def test_all_providers_fail_raises(self) -> None:
        gateway = LLMGateway(
            [_FakeProvider("p1", stream_error=RuntimeError("x"))], max_retries=0
        )

        with pytest.raises(RuntimeError, match="全部模型流式调用失败"):
            await _collect(gateway.stream("prompt"))

    async def test_base_provider_stream_falls_back_to_chat(self) -> None:
        """未覆盖 stream_chat 的 Provider 应整体产出一次（默认实现）。"""
        gateway = LLMGateway([_FakeProvider("p1", deltas=["完整回答"])], max_retries=0)
        assert await _collect(gateway.stream("prompt")) == ["完整回答"]


class TestAgentStream:
    async def test_event_sequence(self) -> None:
        gateway = _FakeGateway(["你好", "，世界"])

        events = [event async for event in stream_answer("问题", gateway=gateway)]

        assert [event.kind for event in events] == [
            StreamKind.CONTEXT,
            StreamKind.DELTA,
            StreamKind.DELTA,
            StreamKind.DONE,
        ]
        assert "".join(event.delta for event in events) == "你好，世界"

    async def test_retrieval_failure_degrades(self) -> None:
        class _BoomRetriever:
            def retrieve(self, query, *, tenant_id, kb_id=None, top_k=None):
                raise RuntimeError("Milvus 不可达")

        events = [
            event
            async for event in stream_answer("问题", gateway=_FakeGateway(), retriever=_BoomRetriever())
        ]

        assert events[0].kind is StreamKind.CONTEXT
        assert events[0].contexts == []

    async def test_history_trimmed(self) -> None:
        seen: list[str] = []

        class _CapturingGateway:
            async def generate(self, prompt, model=None):
                return ""

            async def stream(self, prompt, model=None):
                seen.append(prompt)
                yield "x"

        history = [ChatMessage(role="user", content=f"第{i}轮") for i in range(5)]
        async for _ in stream_answer(
            "问题", gateway=_CapturingGateway(), history=history, history_max_messages=2
        ):
            pass

        assert "第3轮" in seen[0]
        assert "第0轮" not in seen[0]


class TestEventLoopNotBlocked:
    async def test_retrieval_runs_off_event_loop(self) -> None:
        """回归防护：检索是同步阻塞调用，必须放工作线程，否则并发会话会相互排队。"""

        class _BlockingRetriever:
            def retrieve(self, query, *, tenant_id, kb_id=None, top_k=None):
                time.sleep(0.3)  # 模拟同步阻塞（云端嵌入 HTTP + Milvus 检索）
                return []

        ticks = 0

        async def _ticker() -> None:
            nonlocal ticks
            while True:
                await asyncio.sleep(0.02)
                ticks += 1

        ticker = asyncio.create_task(_ticker())
        try:
            async for _ in stream_answer(
                "问题", gateway=_FakeGateway(), retriever=_BlockingRetriever()
            ):
                pass
        finally:
            ticker.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await ticker

        # 阻塞 300ms 期间事件循环应持续心跳（20ms 一次 → 约 14 次）；被阻塞则接近 0
        assert ticks >= 5, f"检索期间事件循环被阻塞（仅 {ticks} 次心跳）"


class TestSessionMessageConsumer:
    async def test_publishes_chunks_and_reply(self) -> None:
        producer = _RecordingProducer()
        config = _config()

        await handle_session_message(
            {"session_id": "s1", "content": "退款多久到账", "message_id": "m-user"},
            config=config,
            producer=producer,
            gateway=_FakeGateway(["一到", "三个工作日"]),
        )

        chunks = producer.by_topic("chunk-topic")
        assert len(chunks) == 3  # 两个内容片段 + 一个终帧
        assert [chunk["index"] for chunk in chunks] == [0, 1, 2]
        assert [chunk["delta"] for chunk in chunks] == ["一到", "三个工作日", ""]
        assert [chunk["finished"] for chunk in chunks] == [False, False, True]
        assert chunks[-1]["error"] == ""
        # 同一条助手消息的 id 在片段与最终回答中保持一致
        assert len({chunk["message_id"] for chunk in chunks}) == 1
        assert chunks[0]["message_id"].startswith("ai-")

        replies = producer.by_topic("reply-topic")
        assert len(replies) == 1
        assert replies[0]["content"] == "一到三个工作日"
        assert replies[0]["message_id"] == chunks[0]["message_id"]
        assert replies[0]["reply_to"] == "m-user"
        assert replies[0]["tenant_id"] == "tenant-default"  # 未携带时用配置兜底

        assert producer.published[0]["key"] == "s1"  # 会话键分区保证有序

    async def test_terminal_frame_on_failure(self) -> None:
        producer = _RecordingProducer()

        await handle_session_message(
            {"session_id": "s2", "content": "问题"},
            config=_config(),
            producer=producer,
            gateway=_FakeBrokenStreamGateway(),
        )

        chunks = producer.by_topic("chunk-topic")
        assert chunks[0]["delta"] == "首片段"
        assert chunks[-1]["finished"] is True
        assert chunks[-1]["error"]  # 失败原因带回终帧
        # 已产出部分内容仍回写最终回答（避免内容丢失）
        assert producer.by_topic("reply-topic")[0]["content"] == "首片段"

    async def test_invalid_event_is_ignored(self) -> None:
        producer = _RecordingProducer()

        await handle_session_message(
            {"content": "无会话标识"}, config=_config(), producer=producer, gateway=_FakeGateway()
        )

        assert producer.published == []

    async def test_payload_builders_shape(self) -> None:
        chunk = build_chunk_payload("s1", "ai-1", 3, "文本", finished=False)
        assert chunk == {
            "session_id": "s1",
            "message_id": "ai-1",
            "index": 3,
            "delta": "文本",
            "finished": False,
            "error": "",
        }
        reply = build_reply_payload("s1", "t1", "ai-1", "全文", "m-user")
        assert reply["reply_to"] == "m-user"
        assert set(reply) == {"session_id", "tenant_id", "message_id", "content", "reply_to"}
