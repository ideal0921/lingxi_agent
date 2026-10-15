"""接管通知消费单测（M4-4.5，离线运行，不连 Kafka）。

覆盖：接管通知写入中断标记（含原因与 agentId 透传）、缺 session_id 的非法消息被忽略
且不抛错、默认 reason 兜底。
"""

from __future__ import annotations

from app.agent.handoff import InterruptRegistry
from app.config import Settings
from app.consumers.session_takeover import (
    handle_session_takeover,
    run_session_takeover_consumer,
)


def _config(**overrides) -> Settings:
    base = {
        "kafka_topic_session_takeover": "takeover-topic",
        "kafka_takeover_consumer_group": "lingxi-ai-takeover",
        "kafka_bootstrap_servers": "127.0.0.1:9092",
    }
    base.update(overrides)
    return Settings(**base)


class TestHandleSessionTakeover:
    async def test_marks_interrupt_with_reason(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)

        await handle_session_takeover(
            {"session_id": "s1", "reason": "agent_takeover", "agent_id": "a1"},
            interrupt_registry=registry,
        )

        assert registry.check("s1") == "agent_takeover"

    async def test_defaults_reason_when_missing(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        await handle_session_takeover({"session_id": "s1"}, interrupt_registry=registry)
        assert registry.check("s1") == "agent_takeover", "缺 reason 时用默认值兜底"

    async def test_handoff_reason_is_preserved(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        await handle_session_takeover(
            {"session_id": "s2", "reason": "handoff_requested"}, interrupt_registry=registry
        )
        assert registry.check("s2") == "handoff_requested"

    async def test_missing_session_id_is_ignored(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        await handle_session_takeover({"reason": "agent_takeover"}, interrupt_registry=registry)
        assert registry.check("") == "", "缺 session_id 的非法消息不应产生任何标记"

    async def test_blank_session_id_is_ignored(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        await handle_session_takeover({"session_id": "   "}, interrupt_registry=registry)
        assert registry.check("") == ""


class TestConsumerWiring:
    async def test_runner_resolves_registry_singleton(self, monkeypatch) -> None:
        """消费器装配：未显式传入中断集合时应从运行时取单例（避免测试污染全局）。"""
        registry = InterruptRegistry(ttl_seconds=60)

        async def fake_get() -> InterruptRegistry:
            return registry

        from app import runtime

        monkeypatch.setattr(runtime, "get_interrupt_registry", fake_get)

        captured: dict = {}

        async def fake_consume_forever(*, topic, group_id, bootstrap_servers, handler, **kwargs):
            captured.update({"topic": topic, "group_id": group_id, "extra": kwargs})
            await handler({"session_id": "s9", "reason": "agent_takeover"})

        monkeypatch.setattr("app.messaging.kafka.consume_forever", fake_consume_forever)

        await run_session_takeover_consumer(config=_config())

        assert captured["topic"] == "takeover-topic"
        assert captured["group_id"] == "lingxi-ai-takeover"
        assert registry.check("s9") == "agent_takeover"
