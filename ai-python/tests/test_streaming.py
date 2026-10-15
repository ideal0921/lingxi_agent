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
from app.agent.handoff import InterruptRegistry
from app.agent.state import ChatMessage, StreamKind
from app.config import Settings
from app.consumers.session_message import (
    build_chunk_payload,
    build_handoff_payload,
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


class _PromptRecordingGateway(_FakeGateway):
    """记录送入模型的提示词（断言长期记忆是否注入）。"""

    def __init__(self, deltas: Sequence[str] = ("片段一",)) -> None:
        super().__init__(deltas)
        self.prompts: list[str] = []

    async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
        self.prompts.append(prompt)
        for delta in self._deltas:
            yield delta


class _MemoryStore:
    """假用户画像存储（可预设画像或模拟异常）。"""

    def __init__(self, *, profile: str = "", fail: bool = False) -> None:
        self.profile = profile
        self._fail = fail

    async def get_profile(self, tenant_id: str, user_id: str) -> str:
        if self._fail:
            raise RuntimeError("redis 不可用")
        return self.profile

    async def save_profile(self, tenant_id: str, user_id: str, profile: str) -> bool:
        return True


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

    async def test_injects_user_memory_into_prompt(self) -> None:
        """跨会话长期记忆（M4-4.4）：带 user_id 时把画像注入提示词。"""
        gateway = _PromptRecordingGateway()

        await handle_session_message(
            {"session_id": "s1", "content": "我上次提过的偏好是什么", "tenant_id": "t1", "user_id": "u1"},
            config=_config(agent_tools_enabled=False),  # 走纯文本提示词路径，便于精确断言
            producer=_RecordingProducer(),
            gateway=gateway,
            memory_store=_MemoryStore(profile="偏好：货到付款；身份：企业采购"),
        )

        assert gateway.prompts, "应调用流式生成"
        assert "【用户长期记忆】" in gateway.prompts[0]
        assert "偏好：货到付款" in gateway.prompts[0]

    async def test_skips_memory_when_no_user_id(self) -> None:
        """无 user_id 时不注入（无法按用户隔离，宁可无记忆）。"""
        gateway = _PromptRecordingGateway()

        await handle_session_message(
            {"session_id": "s1", "content": "问题"},
            config=_config(agent_tools_enabled=False),
            producer=_RecordingProducer(),
            gateway=gateway,
            memory_store=_MemoryStore(profile="偏好：货到付款"),
        )

        assert "【用户长期记忆】" not in gateway.prompts[0]

    async def test_memory_store_failure_degrades(self) -> None:
        """记忆存储异常必须降级：不做记忆注入，但问答主链路照常完成。"""
        producer = _RecordingProducer()
        gateway = _PromptRecordingGateway()

        await handle_session_message(
            {"session_id": "s1", "content": "问题", "user_id": "u1"},
            config=_config(agent_tools_enabled=False),
            producer=producer,
            gateway=gateway,
            memory_store=_MemoryStore(fail=True),
        )

        assert "【用户长期记忆】" not in gateway.prompts[0]
        assert producer.by_topic("reply-topic")[0]["content"] == "片段一", "仍应正常作答"

    async def test_payload_builders_shape(self) -> None:
        chunk = build_chunk_payload("s1", "ai-1", 3, "文本", finished=False)
        assert chunk == {
            "session_id": "s1",
            "message_id": "ai-1",
            "index": 3,
            "delta": "文本",
            "finished": False,
            "error": "",
            "interrupted": False,
            "reason": "",
        }
        # 中断终帧（M4-4.5）：与 error 分开表达，「已转人工」不是错误
        terminal = build_chunk_payload(
            "s1", "ai-1", 5, "", finished=True, interrupted=True, reason="agent_takeover"
        )
        assert terminal["finished"] is True
        assert terminal["interrupted"] is True and terminal["reason"] == "agent_takeover"
        assert terminal["error"] == ""
        reply = build_reply_payload("s1", "t1", "ai-1", "全文", "m-user")
        assert reply["reply_to"] == "m-user"
        assert set(reply) == {"session_id", "tenant_id", "message_id", "content", "reply_to"}
        handoff = build_handoff_payload("s1", "t1", "m-user", "user_request", 7)
        assert handoff == {
            "session_id": "s1",
            "tenant_id": "t1",
            "message_id": "m-user",
            "reason": "user_request",
            "seq": 7,
        }


class _Hit:
    """检索命中替身（仅提供判定与上下文拼装所需字段）。"""

    def __init__(self, score: float = 0.9) -> None:
        self.score = score
        self.heading_path = "政策 > 时效"
        self.doc_id = "d1"
        self.content = "子块内容"
        self.parent_content = "父块内容"


class _RetrieverStub:
    """假检索器：返回预设命中（默认 1 条高分命中，避免误触发低置信度规则）。"""

    def __init__(self, hits: Sequence[_Hit] | None = None) -> None:
        self._hits = list(hits) if hits is not None else [_Hit()]

    def retrieve(self, query: str, *, tenant_id: str, kb_id: str | None = None, top_k: int | None = None):
        return list(self._hits)


class _TakeoverDuringStreamGateway(_PromptRecordingGateway):
    """在产出首个片段之后模拟「接管事件到达」（验证片段检查点确实生效）。"""

    def __init__(self, registry: InterruptRegistry, session_id: str, deltas: Sequence[str]) -> None:
        super().__init__(deltas)
        self._registry = registry
        self._session_id = session_id

    async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
        self.prompts.append(prompt)
        for index, delta in enumerate(self._deltas):
            if index == 1:  # 首个片段已下发后，接管事件到达
                self._registry.mark(self._session_id, "agent_takeover")
            yield delta


class TestHandoffAndInterrupt:
    """M4-4.5：三类规则触发转人工 + 接管瞬间终止生成。"""

    async def test_user_request_forwards_to_human_without_llm(self) -> None:
        producer = _RecordingProducer()
        gateway = _PromptRecordingGateway()

        await handle_session_message(
            {"session_id": "s1", "content": "我要转人工", "message_id": "m1", "tenant_id": "t1"},
            config=_config(agent_tools_enabled=False),
            producer=producer,
            gateway=gateway,
            retriever=_RetrieverStub(),
        )

        assert gateway.prompts == [], "命中转人工规则后不得调用 LLM"
        chunks = producer.by_topic("chunk-topic")
        assert chunks[0]["delta"] == "已为您转接人工客服，请稍候，正在为您安排坐席。"
        assert chunks[-1]["finished"] is True and chunks[-1]["interrupted"] is False
        assert producer.by_topic("reply-topic")[0]["content"] == chunks[0]["delta"], "话术应落库"
        handoffs = producer.by_topic("lingxi.ai.handoff")
        assert len(handoffs) == 1
        assert handoffs[0]["reason"] == "user_request" and handoffs[0]["tenant_id"] == "t1"

    async def test_empty_hits_forwards_to_human(self) -> None:
        """低置信度：配置了检索器但无命中 → 转人工。"""
        producer = _RecordingProducer()

        await handle_session_message(
            {"session_id": "s1", "content": "一个知识库外的问题", "message_id": "m1"},
            config=_config(agent_tools_enabled=False),
            producer=producer,
            gateway=_PromptRecordingGateway(),
            retriever=_RetrieverStub(hits=[]),
        )

        assert producer.by_topic("lingxi.ai.handoff")[0]["reason"] == "low_confidence"

    async def test_no_handoff_without_retriever(self) -> None:
        """未配置知识库时「无命中」是常态，不得据此转人工。"""
        producer = _RecordingProducer()

        await handle_session_message(
            {"session_id": "s1", "content": "退款多久到账", "message_id": "m1"},
            config=_config(agent_tools_enabled=False),
            producer=producer,
            gateway=_PromptRecordingGateway(),
        )

        assert producer.by_topic("lingxi.ai.handoff") == []
        assert producer.by_topic("reply-topic")[0]["content"] == "片段一"

    async def test_takeover_interrupts_generation_and_skips_reply(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        producer = _RecordingProducer()
        gateway = _TakeoverDuringStreamGateway(registry, "s1", deltas=("片段一", "片段二", "片段三"))

        await handle_session_message(
            {"session_id": "s1", "content": "退款多久到账", "message_id": "m1"},
            config=_config(agent_tools_enabled=False),
            producer=producer,
            gateway=gateway,
            retriever=_RetrieverStub(),
            interrupt_registry=registry,
        )

        chunks = producer.by_topic("chunk-topic")
        assert [chunk["delta"] for chunk in chunks[:-1]] == ["片段一"], "中断后不再产出后续片段"
        assert chunks[-1]["finished"] is True
        assert chunks[-1]["interrupted"] is True and chunks[-1]["reason"] == "agent_takeover"
        assert chunks[-1]["error"] == "", "中断不是错误态"
        assert producer.by_topic("reply-topic") == [], "被中断时不得落库半截回答"

    async def test_next_message_is_not_affected_by_stale_flag(self) -> None:
        """中断标记在下一条消息开始处理时被清除，避免误伤后续轮次。"""
        registry = InterruptRegistry(ttl_seconds=60)
        registry.mark("s1", "agent_takeover")
        producer = _RecordingProducer()
        gateway = _PromptRecordingGateway(deltas=("完整回答",))

        await handle_session_message(
            {"session_id": "s1", "content": "退款多久到账", "message_id": "m2"},
            config=_config(agent_tools_enabled=False),
            producer=producer,
            gateway=gateway,
            retriever=_RetrieverStub(),
            interrupt_registry=registry,
        )

        assert producer.by_topic("reply-topic")[0]["content"] == "完整回答"
        assert producer.by_topic("chunk-topic")[-1]["interrupted"] is False

    async def test_memory_maintenance_runs_in_background(self) -> None:
        """消费活性加固（M4-4.5）：记忆维护（摘要/画像）改后台任务，handler 不被阻塞。

        此前摘要/画像在 handler 内 await，最坏耗时（providers × retries × timeout）
        会把消费协程拖过 Kafka `max.poll.interval.ms`，导致被踢出消费组且无法自动恢复。
        """

        class _SlowGateway(_PromptRecordingGateway):
            async def generate(self, prompt: str, model: str | None = None) -> str:
                await asyncio.sleep(0.6)  # 模拟非关键路径的 LLM 抖动
                return "压缩后的要点摘要"

        producer = _RecordingProducer()
        config = _config(agent_tools_enabled=False, agent_history_max_messages=2)
        history = [{"role": "user", "content": f"第{i}轮"} for i in range(5)]

        started = time.perf_counter()
        await handle_session_message(
            {"session_id": "s1", "content": "退款多久到账", "message_id": "m1", "history": history},
            config=config,
            producer=producer,
            gateway=_SlowGateway(),
            retriever=_RetrieverStub(),
        )
        elapsed = time.perf_counter() - started

        assert elapsed < 0.5, "handler 不应等待后台记忆维护任务"
        assert producer.by_topic(config.kafka_topic_session_summary) == [], "摘要回写应在后台进行"
        await asyncio.sleep(0.9)
        assert producer.by_topic(config.kafka_topic_session_summary)[0]["summary"] == "压缩后的要点摘要"
