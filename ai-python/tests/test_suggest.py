"""坐席快捷回复同步通道单测（M4-4.6，离线运行，不触网 / 不连 Kafka / 不起真实端口）。

覆盖：提示词拼装（含摘要/历史/意图）、候选解析（去编号/去重/截断）、并发闸门（第 33 路
立即降级、不排队、统计回落到 0）、Servicer（正常返回 / 闸门满降级 BUSY / 生成超时降级
TIMEOUT / 异常降级 UNAVAILABLE / PII 脱敏与还原），以及异步降级消费者的结果投递。
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest

from app.agent.state import ChatMessage
from app.agent.suggest import (
    ConcurrencyGate,
    build_suggest_prompt,
    parse_candidates,
)
from app.config import Settings

# 生成的契约目录（与服务端一致）
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "proto"))
import agent_sync_pb2 as agent_pb


class _Gateway:
    """假 LLM 网关：可返回固定文本或抛错/挂起。"""

    def __init__(self, text: str = "您好，我帮您核实一下。\n请提供订单号，我为您查询。", *, error=None, hang=0.0) -> None:
        self._text = text
        self._error = error
        self._hang = hang
        self.prompts: list[str] = []

    async def generate(self, prompt: str, model: str | None = None) -> str:
        self.prompts.append(prompt)
        if self._hang:
            await asyncio.sleep(self._hang)
        if self._error is not None:
            raise self._error
        return self._text


def _config(**overrides) -> Settings:
    base = {
        "suggest_max_candidates": 3,
        "suggest_timeout_seconds": 3.5,
        "suggest_max_concurrent_rpcs": 64,
        "pii_enabled": True,
        "pii_mask_address": True,
    }
    base.update(overrides)
    return Settings(**base)


def _request(**overrides):
    values = {
        "request_id": "req-1",
        "tenant_id": "t1",
        "session_id": "s1",
        "agent_id": "a1",
        "intent": "",
        "summary": "",
        "max_suggestions": 0,
        "history": [],
    }
    values.update(overrides)
    return agent_pb.GenerateSuggestRequest(
        request_id=values["request_id"],
        tenant_id=values["tenant_id"],
        session_id=values["session_id"],
        agent_id=values["agent_id"],
        intent=values["intent"],
        summary=values["summary"],
        max_suggestions=values["max_suggestions"],
        history=[agent_pb.ChatTurn(role=t["role"], content=t["content"]) for t in values["history"]],
    )


class TestPrompt:
    def test_includes_summary_history_and_intent(self) -> None:
        prompt = build_suggest_prompt(
            "用户咨询退款",
            [ChatMessage(role="user", content="退款多久到账")],
            "安抚并说明时效",
            3,
        )
        assert "回复候选" in prompt
        assert "【会话摘要】" in prompt and "用户咨询退款" in prompt
        assert "【近期对话】" in prompt and "退款多久到账" in prompt
        assert "【坐席补充意图】" in prompt and "安抚并说明时效" in prompt
        assert "不要编号" in prompt

    def test_empty_history_uses_placeholder(self) -> None:
        prompt = build_suggest_prompt("", [], "", 2)
        assert "（暂无）" in prompt


class TestParseCandidates:
    def test_strips_marker_and_dedupes(self) -> None:
        text = "1. 您好，我帮您核实。\n2、请提供订单号。\n- 您好，我帮您核实。\n\n3) 感谢您的耐心等待。"
        assert parse_candidates(text, 5) == [
            "您好，我帮您核实。",
            "请提供订单号。",
            "感谢您的耐心等待。",
        ]

    def test_caps_to_limit(self) -> None:
        text = "\n".join(f"候选{i}" for i in range(6))
        assert len(parse_candidates(text, 3)) == 3

    def test_blank_returns_empty(self) -> None:
        assert parse_candidates("", 3) == []


class TestConcurrencyGate:
    async def test_rejects_when_full_without_queueing(self) -> None:
        gate = ConcurrencyGate(limit=2, queue_wait_seconds=0.0)
        assert await gate.acquire() is True
        assert await gate.acquire() is True
        assert await gate.acquire() is False, "闸门满应立即降级（不排队）"
        assert gate.snapshot()["in_flight"] == 2

    async def test_release_frees_slot_and_stats_return_to_zero(self) -> None:
        gate = ConcurrencyGate(limit=1, queue_wait_seconds=0.0)
        assert await gate.acquire() is True
        assert await gate.acquire() is False
        gate.release()
        assert gate.snapshot()["in_flight"] == 0, "释放后应在飞数归零（不泄漏）"
        assert await gate.acquire() is True

    async def test_counts_busy_degradation(self) -> None:
        gate = ConcurrencyGate(limit=1, queue_wait_seconds=0.0)
        await gate.acquire()
        await gate.acquire()
        await gate.acquire()
        assert gate.snapshot()["busy_degraded"] == 2


class TestServicer:
    async def _servicer(self, gateway, config=None, gate=None):
        from app.sync.server import SuggestServicer

        return SuggestServicer(
            gateway=gateway, config=config or _config(), gate=gate or ConcurrencyGate(limit=32)
        )

    async def test_returns_candidates(self) -> None:
        gateway = _Gateway()
        servicer = await self._servicer(gateway)

        response = await servicer.SuggestReplies(_request(), context=None)

        assert response.ok is True and response.degraded is False
        assert len(response.suggestions) == 2
        assert "【近期对话】" in gateway.prompts[0]
        assert response.elapsed_ms >= 0

    async def test_degrades_busy_when_gate_full(self) -> None:
        gate = ConcurrencyGate(limit=1, queue_wait_seconds=0.0)
        await gate.acquire()
        servicer = await self._servicer(_Gateway(), gate=gate)

        response = await servicer.SuggestReplies(_request(), context=None)

        assert response.ok is False and response.degraded is True
        assert response.degrade_reason == "BUSY"

    async def test_degrades_on_timeout(self) -> None:
        servicer = await self._servicer(
            _Gateway(hang=0.2), config=_config(suggest_timeout_seconds=0.05)
        )

        response = await servicer.SuggestReplies(_request(), context=None)

        assert response.degraded is True and response.degrade_reason == "TIMEOUT"

    async def test_degrades_on_generation_error(self) -> None:
        servicer = await self._servicer(_Gateway(error=RuntimeError("上游不可用")))

        response = await servicer.SuggestReplies(_request(), context=None)

        assert response.degraded is True and response.degrade_reason == "UNAVAILABLE"

    async def test_releases_gate_after_request(self) -> None:
        gate = ConcurrencyGate(limit=4)
        servicer = await self._servicer(_Gateway(), gate=gate)

        await servicer.SuggestReplies(_request(), context=None)

        assert gate.snapshot()["in_flight"] == 0, "请求结束必须释放闸门（否则泄漏）"

    async def test_pii_masked_before_model_and_restored_in_candidates(self) -> None:
        """进模型前脱敏（手机号变占位符）、候选返回前还原。"""
        gateway = _Gateway(text="已为您登记，请联系 13800001111 确认。")
        servicer = await self._servicer(gateway)

        response = await servicer.SuggestReplies(
            _request(intent="回访用户 13800001111", history=[{"role": "user", "content": "我的手机号 13800001111"}]),
            context=None,
        )

        assert "13800001111" not in gateway.prompts[0], "提示词中不得出现真实手机号"
        assert "[手机号1]" in gateway.prompts[0]
        assert any("13800001111" in item for item in response.suggestions), "候选应还原为原文"

    async def test_history_roles_are_filtered(self) -> None:
        gateway = _Gateway()
        servicer = await self._servicer(gateway)

        await servicer.SuggestReplies(
            _request(history=[{"role": "tool", "content": "非法角色"}, {"role": "user", "content": "正常"}]),
            context=None,
        )

        assert "非法角色" not in gateway.prompts[0] and "正常" in gateway.prompts[0]


class _Producer:
    def __init__(self) -> None:
        self.published: list[dict] = []

    async def publish(self, topic: str, payload: dict, key: str | None = None) -> None:
        self.published.append({"topic": topic, "payload": payload, "key": key})


class TestAsyncFallback:
    async def test_generates_and_publishes_result(self) -> None:
        from app.consumers.suggest_request import handle_suggest_request

        producer, gateway = _Producer(), _Gateway()
        config = _config(kafka_topic_ai_suggest_result="suggest-result")

        await handle_suggest_request(
            {
                "request_id": "req-9",
                "session_id": "s1",
                "intent": "安抚",
                "summary": "用户咨询退款",
                "history": [{"role": "user", "content": "退款多久"}],
            },
            gateway=gateway,
            producer=producer,
            config=config,
        )

        assert producer.published[0]["topic"] == "suggest-result"
        assert producer.published[0]["key"] == "s1", "会话键分区（与同会话事件保序）"
        assert producer.published[0]["payload"]["request_id"] == "req-9"
        assert len(producer.published[0]["payload"]["suggestions"]) == 2

    async def test_ignores_invalid_event(self) -> None:
        from app.consumers.suggest_request import handle_suggest_request

        producer = _Producer()
        await handle_suggest_request(
            {"session_id": "", "request_id": ""}, gateway=_Gateway(), producer=producer, config=_config()
        )
        assert producer.published == []

    async def test_generation_failure_does_not_raise(self) -> None:
        from app.consumers.suggest_request import handle_suggest_request

        producer = _Producer()
        await handle_suggest_request(
            {"request_id": "r1", "session_id": "s1"},
            gateway=_Gateway(error=RuntimeError("上游不可用")),
            producer=producer,
            config=_config(),
        )
        assert producer.published == [], "失败不投递、也不抛错（不打断消费循环）"


@pytest.mark.parametrize("port", [9095])
def test_default_port(port: int) -> None:
    assert _config().suggest_grpc_port == port
