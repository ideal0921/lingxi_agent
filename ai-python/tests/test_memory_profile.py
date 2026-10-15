"""用户长期画像单测（M4-4.4，离线运行，不触网 / 不真连 Redis）。

覆盖：画像提示词拼装、增量合并（失败退回原画像、空溢出不调模型）、入库前净化
（去占位符与 PII）、超窗时画像写入触发条件（缺 user_id/开关关闭/未超窗均不写、
存储异常不影响摘要投递）。
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Sequence

from app.agent.memory import build_profile_prompt, merge_profile, sanitize_profile
from app.agent.state import ChatMessage
from app.config import Settings
from app.consumers.session_message import SessionMessageEvent, publish_summary_if_needed


class _Producer:
    def __init__(self) -> None:
        self.published: list[dict] = []

    async def publish(self, topic: str, payload: dict, key: str | None = None) -> None:
        self.published.append({"topic": topic, "payload": payload, "key": key})


class _Gateway:
    """按提示词类型分派：画像提示返回画像，摘要提示返回摘要。"""

    def __init__(self, *, profile: str = "偏好：货到付款；身份：企业采购", summary: str = "压缩要点摘要") -> None:
        self._profile = profile
        self._summary = summary
        self.prompts: list[str] = []

    async def generate(self, prompt: str, model: str | None = None) -> str:
        self.prompts.append(prompt)
        if "用户画像维护助手" in prompt:
            return self._profile
        if "对话摘要助手" in prompt:
            return self._summary
        return "回答"

    async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
        yield "回答"


class _BoomGateway:
    async def generate(self, prompt: str, model: str | None = None) -> str:
        raise RuntimeError("模型不可用")

    async def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]:
        raise RuntimeError("模型不可用")


class _Store:
    """假画像存储：可预设已有画像，并记录写入。"""

    def __init__(self, *, profile: str = "", fail: bool = False) -> None:
        self.profile = profile
        self._fail = fail
        self.saved: list[tuple[str, str, str]] = []

    async def get_profile(self, tenant_id: str, user_id: str) -> str:
        if self._fail:
            raise RuntimeError("redis 不可用")
        return self.profile

    async def save_profile(self, tenant_id: str, user_id: str, profile: str) -> bool:
        self.saved.append((tenant_id, user_id, profile))
        return True


def _config(**overrides) -> Settings:
    base = {
        "kafka_topic_session_summary": "summary-topic",
        "agent_history_max_messages": 2,
        "agent_summary_enabled": True,
        "agent_summary_max_chars": 600,
        "agent_memory_merge_enabled": True,
        "agent_memory_max_chars": 400,
    }
    base.update(overrides)
    return Settings(**base)


def _event(history: Sequence[ChatMessage], *, user_id: str = "u1", tenant_id: str = "t1") -> SessionMessageEvent:
    return SessionMessageEvent(
        session_id="s1", content="本轮问题", message_id="m1", user_id=user_id,
        tenant_id=tenant_id, history=list(history),
    )


def _overflow_history() -> list[ChatMessage]:
    return [ChatMessage(role="user", content=f"第{i}轮") for i in range(5)]  # 窗口=2，溢出 3 条


class TestProfilePrompt:
    def test_includes_instruction_and_previous_profile(self) -> None:
        prompt = build_profile_prompt("偏好：夜间联系", [ChatMessage(role="user", content="我常买办公用品")], 400)
        assert "用户画像维护助手" in prompt
        assert "禁止" in prompt and "[手机号1]" in prompt, "必须显式禁止记录 PII 与占位符"
        assert "偏好：夜间联系" in prompt
        assert "我常买办公用品" in prompt

    def test_empty_previous_profile_uses_placeholder(self) -> None:
        prompt = build_profile_prompt("", [ChatMessage(role="user", content="问题")], 400)
        assert "（暂无）" in prompt


class TestMergeProfile:
    async def test_merges_and_sanitizes(self) -> None:
        gateway = _Gateway(profile="偏好：货到付款")
        result = await merge_profile(gateway, "旧画像", [ChatMessage(role="user", content="新偏好")])
        assert result == "偏好：货到付款"
        assert "旧画像" in gateway.prompts[0], "已有画像应参与增量合并"

    async def test_failure_falls_back_to_previous(self) -> None:
        result = await merge_profile(_BoomGateway(), "旧画像", [ChatMessage(role="user", content="新偏好")])
        assert result == "旧画像", "合并失败应沿用原画像，不阻断主流程"

    async def test_empty_overflow_returns_previous(self) -> None:
        gateway = _Gateway()
        assert await merge_profile(gateway, "旧画像", []) == "旧画像"
        assert gateway.prompts == [], "无溢出消息时不调用模型"

    async def test_output_with_pii_is_sanitized(self) -> None:
        """模型即便违规输出占位符/手机号，入库前也已被抹除。"""
        gateway = _Gateway(profile="用户手机号 [手机号1] 为 13800001111，常住杭州市")
        result = await merge_profile(gateway, "", [ChatMessage(role="user", content="联系我")])
        assert "[手机号1]" not in result
        assert "13800001111" not in result
        assert "（手机号）" in result

    async def test_hard_timeout_falls_back_to_previous(self) -> None:
        """消费活性加固（M4-4.5）：画像合并同属非关键路径，须有硬性超时兜底。"""

        class _HangGateway:
            async def generate(self, prompt: str, model: str | None = None) -> str:
                await asyncio.sleep(5)
                return "不应返回"

        result = await merge_profile(
            _HangGateway(), "旧画像", [ChatMessage(role="user", content="新偏好")], timeout_seconds=0.05
        )
        assert result == "旧画像", "超时应沿用原画像而非阻塞"


class TestSanitizeProfile:
    def test_strips_placeholders_and_redacts_pii(self) -> None:
        text = "手机号 [手机号1]，实际 13800001111；邮箱 a@b.com"
        result = sanitize_profile(text)
        assert "[手机号1]" not in result
        assert "13800001111" not in result
        assert "a@b.com" not in result
        assert "（手机号）" in result and "（邮箱）" in result

    def test_truncates_to_max_chars(self) -> None:
        assert sanitize_profile("字" * 100, max_chars=20) == "字" * 20

    def test_empty_input_returns_empty(self) -> None:
        assert sanitize_profile("") == ""


class TestProfileWriteBack:
    async def test_writes_profile_when_history_overflows(self) -> None:
        producer, store = _Producer(), _Store(profile="旧画像")
        gateway = _Gateway(profile="偏好：货到付款")

        await publish_summary_if_needed(
            _event(_overflow_history()), gateway=gateway, producer=producer, config=_config(), store=store
        )

        assert store.saved == [("t1", "u1", "偏好：货到付款")]
        assert len(producer.published) == 1, "会话摘要仍应照常投递"

    async def test_no_profile_write_when_within_window(self) -> None:
        producer, store = _Producer(), _Store()
        await publish_summary_if_needed(
            _event([ChatMessage(role="user", content="第1轮")]),
            gateway=_Gateway(), producer=producer, config=_config(), store=store,
        )
        assert store.saved == [] and producer.published == []

    async def test_no_profile_write_when_merge_disabled(self) -> None:
        store = _Store()
        await publish_summary_if_needed(
            _event(_overflow_history()), gateway=_Gateway(), producer=_Producer(),
            config=_config(agent_memory_merge_enabled=False), store=store,
        )
        assert store.saved == []

    async def test_no_profile_write_without_user_id(self) -> None:
        store = _Store()
        await publish_summary_if_needed(
            _event(_overflow_history(), user_id=""), gateway=_Gateway(), producer=_Producer(),
            config=_config(), store=store,
        )
        assert store.saved == []

    async def test_no_profile_write_without_store(self) -> None:
        await publish_summary_if_needed(
            _event(_overflow_history()), gateway=_Gateway(), producer=_Producer(),
            config=_config(), store=None,
        )

    async def test_store_failure_does_not_break_summary_publish(self) -> None:
        """存储异常必须被吞掉：摘要照常投递，且不向上抛异常。"""
        producer = _Producer()
        await publish_summary_if_needed(
            _event(_overflow_history()), gateway=_Gateway(summary="新摘要"),
            producer=producer, config=_config(), store=_Store(fail=True),
        )
        assert len(producer.published) == 1, "摘要投递不受画像存储异常影响"

    async def test_unchanged_profile_is_not_rewritten(self) -> None:
        store = _Store(profile="旧画像")
        await publish_summary_if_needed(
            _event(_overflow_history()), gateway=_Gateway(profile="旧画像"), producer=_Producer(),
            config=_config(), store=store,
        )
        assert store.saved == [], "画像未变化时避免重复写入"
