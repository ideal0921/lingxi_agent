"""上下文窗口与长程摘要单测（M3-3.11，离线运行，不连 Kafka / 不触网 / 不连 Milvus）。

覆盖：会话事件的历史/摘要解析（含脏数据跳过）、摘要注入提示词、超窗时压缩旧消息并
回写会话摘要、未超窗/开关关闭/摘要失败时不产生多余事件。
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Sequence

from app.agent.graph import build_prompt
from app.agent.memory import summarize_history
from app.agent.state import ChatMessage
from app.config import Settings
from app.consumers.session_message import (
    SessionMessageEvent,
    publish_summary_if_needed,
)


class _FakeProducer:
    def __init__(self) -> None:
        self.published: list[dict] = []

    async def publish(self, topic: str, payload: dict, key: str | None = None) -> None:
        self.published.append({"topic": topic, "payload": payload, "key": key})

    def by_topic(self, topic: str) -> list[dict]:
        return [item["payload"] for item in self.published if item["topic"] == topic]


class _FakeGateway:
    """按提示词内容分派回答：摘要提示返回摘要，生成提示返回回答片段。"""

    def __init__(self, summary: str = "用户此前咨询过退款时效", answer: Sequence[str] = ("回答",)) -> None:
        self._summary = summary
        self._answer = list(answer)
        self.prompts: list[str] = []

    async def generate(self, prompt: str, model: str | None = None) -> str:
        self.prompts.append(prompt)
        if "对话摘要助手" in prompt:
            return self._summary
        return "".join(self._answer)

    async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
        self.prompts.append(prompt)
        for part in self._answer:
            yield part


class _BoomGateway(_FakeGateway):
    """摘要生成失败（模型不可用）的替身。"""

    async def generate(self, prompt: str, model: str | None = None) -> str:
        self.prompts.append(prompt)
        raise RuntimeError("模型不可用")


def _config(**overrides) -> Settings:
    base = {
        "kafka_topic_session_summary": "summary-topic",
        "agent_history_max_messages": 2,
        "agent_summary_enabled": True,
        "agent_summary_max_chars": 600,
    }
    base.update(overrides)
    return Settings(**base)


def _event(history: Sequence[ChatMessage], summary: str = "") -> SessionMessageEvent:
    return SessionMessageEvent(
        session_id="s1", content="本轮问题", message_id="m1", summary=summary, history=list(history)
    )


class TestEventParsing:
    def test_parses_summary_and_history(self) -> None:
        event = SessionMessageEvent.from_payload(
            {
                "session_id": "s1",
                "content": "本轮问题",
                "summary": "用户此前咨询退款",
                "history": [
                    {"role": "user", "content": "退款多久"},
                    {"role": "agent", "content": "3-7 个工作日"},
                ],
            }
        )

        assert event.summary == "用户此前咨询退款"
        assert [m.role for m in event.history] == ["user", "agent"]
        assert event.history[1].content == "3-7 个工作日"

    def test_skips_malformed_history_entries(self) -> None:
        event = SessionMessageEvent.from_payload(
            {
                "session_id": "s1",
                "content": "问题",
                "history": [
                    {"role": "user", "content": "正常"},
                    {"role": "tool", "content": "非法角色"},
                    {"role": "user", "content": ""},
                    "not-a-dict",
                ],
            }
        )

        assert len(event.history) == 1, "非法角色/空内容/非对象条目应被跳过"
        assert event.history[0].content == "正常"

    def test_missing_history_defaults_to_empty(self) -> None:
        event = SessionMessageEvent.from_payload({"session_id": "s1", "content": "问题"})
        assert event.history == [] and event.summary == ""
        assert event.user_id == "", "缺 user_id 时默认为空（调用侧据此跳过记忆功能）"

    def test_parses_user_id(self) -> None:
        event = SessionMessageEvent.from_payload(
            {"session_id": "s1", "content": "问题", "tenant_id": "t1", "user_id": "u1"}
        )
        assert event.user_id == "u1" and event.tenant_id == "t1"


class TestPromptInjection:
    def test_summary_section_precedes_history(self) -> None:
        prompt = build_prompt(
            {
                "query": "那换货呢",
                "summary": "用户此前咨询退款时效，已告知 3-7 个工作日",
                "history": [ChatMessage(role="user", content="退款多久")],
                "context_text": "知识内容",
            }
        )

        assert "【会话摘要】" in prompt
        assert "已告知 3-7 个工作日" in prompt
        assert prompt.index("【会话摘要】") < prompt.index("【对话历史】")

    def test_no_summary_section_when_empty(self) -> None:
        prompt = build_prompt({"query": "问题", "summary": "", "history": [], "context_text": ""})
        assert "【会话摘要】" not in prompt

    def test_memory_section_precedes_summary(self) -> None:
        """分层记忆（M4-4.4）：跨会话长期画像应排在会话内摘要之前。"""
        prompt = build_prompt(
            {
                "query": "问题",
                "memory": "偏好：货到付款；身份：企业采购",
                "summary": "用户此前咨询退款时效",
                "history": [],
                "context_text": "",
            }
        )

        assert "【用户长期记忆】" in prompt and "偏好：货到付款" in prompt
        assert prompt.index("【用户长期记忆】") < prompt.index("【会话摘要】")
        assert prompt.index("【会话摘要】") < prompt.index("【知识片段】")

    def test_no_memory_section_when_empty(self) -> None:
        prompt = build_prompt({"query": "问题", "memory": "", "summary": "", "history": [], "context_text": ""})
        assert "【用户长期记忆】" not in prompt


class TestSummarizeHistory:
    async def test_returns_summary_and_caps_length(self) -> None:
        gateway = _FakeGateway(summary="摘要" * 100)
        result = await summarize_history(
            gateway, [ChatMessage(role="user", content="旧问题")], previous_summary="已有摘要", max_chars=10
        )
        assert result == "摘要" * 5, "摘要应被截断到上限长度"
        assert "已有摘要" in gateway.prompts[0], "已有摘要应参与增量压缩"

    async def test_failure_falls_back_to_previous_summary(self) -> None:
        result = await summarize_history(
            _BoomGateway(), [ChatMessage(role="user", content="旧问题")], previous_summary="旧摘要"
        )
        assert result == "旧摘要", "摘要失败应沿用原摘要，不阻断主流程"

    async def test_empty_overflow_returns_previous(self) -> None:
        gateway = _FakeGateway()
        assert await summarize_history(gateway, [], previous_summary="旧摘要") == "旧摘要"
        assert gateway.prompts == [], "无溢出消息时不调用模型"

    async def test_hard_timeout_falls_back_to_previous(self) -> None:
        """消费活性加固（M4-4.5）：非关键路径硬性超时——网关挂起也不得长时间占用资源。

        网关内部最坏耗时 = providers × (retries+1) × timeout，实测曾把消费协程阻塞到
        超过 Kafka `max.poll.interval.ms` 而被踢出消费组。
        """

        class _HangGateway:
            async def generate(self, prompt: str, model: str | None = None) -> str:
                await asyncio.sleep(5)
                return "不应返回"

        result = await summarize_history(
            _HangGateway(),
            [ChatMessage(role="user", content="旧问题")],
            previous_summary="旧摘要",
            timeout_seconds=0.05,
        )

        assert result == "旧摘要", "超时应沿用原摘要而非阻塞"


class TestSummaryWriteBack:
    async def test_publishes_summary_when_history_overflows(self) -> None:
        producer = _FakeProducer()
        gateway = _FakeGateway(summary="压缩后的要点摘要")
        history = [ChatMessage(role="user", content=f"第{i}轮") for i in range(5)]

        await publish_summary_if_needed(
            _event(history), gateway=gateway, producer=producer, config=_config()
        )

        published = producer.by_topic("summary-topic")
        assert len(published) == 1
        assert published[0]["session_id"] == "s1"
        assert published[0]["summary"] == "压缩后的要点摘要"
        assert producer.published[0]["key"] == "s1"  # 会话键分区

    async def test_no_publish_when_within_window(self) -> None:
        producer = _FakeProducer()
        history = [ChatMessage(role="user", content="第1轮")]  # 窗口=2，未超出

        await publish_summary_if_needed(
            _event(history), gateway=_FakeGateway(), producer=producer, config=_config()
        )

        assert producer.published == []

    async def test_no_publish_when_disabled(self) -> None:
        producer = _FakeProducer()
        history = [ChatMessage(role="user", content=f"第{i}轮") for i in range(5)]

        await publish_summary_if_needed(
            _event(history),
            gateway=_FakeGateway(),
            producer=producer,
            config=_config(agent_summary_enabled=False),
        )

        assert producer.published == []

    async def test_no_publish_on_summary_failure(self) -> None:
        producer = _FakeProducer()
        history = [ChatMessage(role="user", content=f"第{i}轮") for i in range(5)]

        await publish_summary_if_needed(
            _event(history, summary="旧摘要"), gateway=_BoomGateway(), producer=producer, config=_config()
        )

        assert producer.published == [], "摘要生成失败时不应投递空/重复摘要"

    async def test_unchanged_summary_is_not_republished(self) -> None:
        producer = _FakeProducer()
        history = [ChatMessage(role="user", content=f"第{i}轮") for i in range(5)]
        gateway = _FakeGateway(summary="旧摘要")

        await publish_summary_if_needed(
            _event(history, summary="旧摘要"), gateway=gateway, producer=producer, config=_config()
        )

        assert producer.published == [], "摘要未变化时避免重复回写"
