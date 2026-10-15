"""转人工规则与生成中断标记单测（M4-4.5，离线运行，不触网 / 不连 Kafka）。

覆盖：三类规则（用户明示 / 情绪 / 低置信度）的命中与不命中、优先级、大小写与空白归一、
各开关与阈值、以及 :class:`InterruptRegistry` 的写入/读取/TTL/清理语义。
"""

from __future__ import annotations

import time

from app.agent.handoff import (
    REASON_EMOTION,
    REASON_LOW_CONFIDENCE,
    REASON_USER_REQUEST,
    InterruptRegistry,
    decide_handoff,
)

_USER_KEYWORDS = "转人工,人工客服,人工服务,找人工,转接人工"
_EMOTION_KEYWORDS = "投诉,举报,骗子,诈骗,垃圾,退钱,赔偿,维权,差评,监管,报警,告你们"


def _decide(query: str, scores=(0.9,), **overrides):
    base = {
        "user_keywords": _USER_KEYWORDS,
        "emotion_keywords": _EMOTION_KEYWORDS,
        "emotion_min_hits": 2,
        "empty_hits_enabled": True,
        "low_score_enabled": False,
        "low_score_threshold": 0.3,
    }
    base.update(overrides)
    return decide_handoff(query, list(scores), **base)


class TestUserRequestRule:
    def test_matches_explicit_request(self) -> None:
        decision = _decide("我要转人工")
        assert decision.handoff is True and decision.reason == REASON_USER_REQUEST

    def test_matches_with_whitespace_and_case(self) -> None:
        """归一化：去掉空白、忽略大小写后仍能命中。"""
        assert _decide("  请帮我 转 人 工  ").handoff is True
        assert _decide("我要转人工").handoff is True

    def test_does_not_match_unrelated_question(self) -> None:
        decision = _decide("退款多久到账")
        assert decision.handoff is False and decision.reason == ""


class TestEmotionRule:
    def test_matches_when_threshold_reached(self) -> None:
        decision = _decide("你们这是诈骗，我要投诉！")
        assert decision.handoff is True and decision.reason == REASON_EMOTION

    def test_single_hit_below_threshold_does_not_trigger(self) -> None:
        """单词命中不触发，避免误伤（如正常提到「投诉渠道」）。"""
        decision = _decide("请问投诉渠道在哪里")
        assert decision.handoff is False

    def test_threshold_is_configurable(self) -> None:
        decision = _decide("我要投诉", emotion_min_hits=1)
        assert decision.handoff is True and decision.reason == REASON_EMOTION


class TestLowConfidenceRule:
    def test_empty_hits_triggers_when_enabled(self) -> None:
        decision = _decide("一个知识库外的问题", scores=[])
        assert decision.handoff is True and decision.reason == REASON_LOW_CONFIDENCE

    def test_empty_hits_disabled_does_not_trigger(self) -> None:
        assert _decide("问题", scores=[], empty_hits_enabled=False).handoff is False

    def test_low_score_disabled_by_default(self) -> None:
        """精排分与 RRF 分量纲不同不可比，故低分阈值规则默认关闭。"""
        assert _decide("问题", scores=[0.05]).handoff is False

    def test_low_score_triggers_when_explicitly_enabled(self) -> None:
        decision = _decide("问题", scores=[0.05, 0.02], low_score_enabled=True)
        assert decision.handoff is True and decision.reason == REASON_LOW_CONFIDENCE

    def test_high_score_does_not_trigger(self) -> None:
        assert _decide("问题", scores=[0.95], low_score_enabled=True).handoff is False


class TestPriority:
    def test_user_request_wins_over_emotion_and_confidence(self) -> None:
        decision = _decide("我要投诉你们这个骗子，转人工", scores=[])
        assert decision.reason == REASON_USER_REQUEST

    def test_emotion_wins_over_confidence(self) -> None:
        decision = _decide("你们就是骗子，我要投诉", scores=[])
        assert decision.reason == REASON_EMOTION


class TestEmptyInputs:
    def test_blank_query_does_not_trigger_text_rules(self) -> None:
        assert _decide("", scores=[0.9]).handoff is False

    def test_blank_query_with_empty_hits(self) -> None:
        decision = _decide("", scores=[])
        assert decision.reason == REASON_LOW_CONFIDENCE


class TestInterruptRegistry:
    def test_mark_and_check(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        assert registry.check("s1") == "", "未标记时不应中断"
        registry.mark("s1", "agent_takeover")
        assert registry.check("s1") == "agent_takeover"

    def test_check_is_repeatable(self) -> None:
        """只读不删：同一轮可能多次检查（多片段）。"""
        registry = InterruptRegistry(ttl_seconds=60)
        registry.mark("s1", "handoff_requested")
        assert registry.check("s1") == "handoff_requested"
        assert registry.check("s1") == "handoff_requested"

    def test_sessions_are_isolated(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        registry.mark("s1", "agent_takeover")
        assert registry.check("s2") == "", "标记按会话隔离，不得误伤其他会话"

    def test_clear_removes_flag(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        registry.mark("s1", "agent_takeover")
        registry.clear("s1")
        assert registry.check("s1") == ""

    def test_expired_flag_is_ignored(self) -> None:
        registry = InterruptRegistry(ttl_seconds=0.01)
        registry.mark("s1", "agent_takeover")
        time.sleep(0.03)
        assert registry.check("s1") == "", "TTL 过期后不应再中断"

    def test_blank_session_id_is_ignored(self) -> None:
        registry = InterruptRegistry(ttl_seconds=60)
        registry.mark("", "agent_takeover")
        assert registry.check("") == ""
