"""长程记忆（M3-3.11 会话级摘要 + M4-4.4 跨会话用户级画像）。

分层记忆（《架构设计》7.5）::

    会话上下文 = 【用户长期记忆（画像）】 + 【会话摘要】 + 最近 N 轮原文 + 本轮检索知识

- **会话摘要（M3-3.11）**：把超出上下文窗口的旧消息压缩为要点摘要，经
  ``lingxi.session.summary`` 回写 Java 会话，随下一轮事件带回（进程重启不丢）。
- **用户画像（M4-4.4）**：把用户**跨会话**都值得记住的长期信息（偏好、身份角色、
  长期约束、反复诉求）沉淀为每用户 1 条画像，存 Redis（``app/agent/memory_store.py``），
  新会话每轮注入提示词。

实现要点：
- **增量压缩**：入参包含「已有摘要/已有画像」，新旧合并后再压缩，避免每次重算全量；
- **失败不阻断**：生成失败（模型不可用等）时返回原值，不影响本轮应答；
- **长度受控**：按配置上限截断，避免随事件/存储无限膨胀；
- **画像不含 PII（M4-4.4）**：提示词禁止记录具体个人信息与占位符，写入前再经
  :func:`sanitize_profile` 兜底净化——跨会话无法还原占位符，留存即泄漏。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Sequence
from typing import Protocol

from app.agent.state import ChatMessage
from app.privacy.pii import redact_pii, strip_placeholders

logger = logging.getLogger(__name__)


class Summarizer(Protocol):
    """摘要所需的 LLM 能力（与 :class:`app.gateway.router.LLMGateway` 形态一致）。"""

    async def generate(self, prompt: str, model: str | None = None) -> str: ...


async def _generate_with_timeout(
    gateway: Summarizer, prompt: str, timeout_seconds: float | None
) -> str:
    """调用 LLM 生成，可选**硬性总超时**（覆盖网关内部重试/退避的累计耗时）。

    记忆维护（摘要 / 画像合并）属非关键路径：网关内部最坏耗时为
    ``providers × (retries+1) × timeout``，实测曾因单次抖动把消费协程阻塞到超过
    Kafka ``max.poll.interval.ms`` 而被踢出消费组。故此处以上限兜底。
    """
    if timeout_seconds is None or timeout_seconds <= 0:
        return await gateway.generate(prompt)
    return await asyncio.wait_for(gateway.generate(prompt), timeout=timeout_seconds)


_SUMMARY_INSTRUCTION = (
    "你是对话摘要助手。请把给出的历史对话压缩为**要点式中文摘要**，"
    "保留：用户的核心诉求、已确认的事实与结论、待办或未解决的问题、重要约束（如时效/金额/订单）。"
    "不要编造对话中未出现的信息，不要输出寒暄。"
)


def build_summary_prompt(overflow: Sequence[ChatMessage], previous_summary: str, max_chars: int) -> str:
    """拼装摘要提示词（已有摘要 + 新增旧对话）。"""
    parts: list[str] = [_SUMMARY_INSTRUCTION, f"摘要请控制在 {max_chars} 字以内。"]
    if previous_summary.strip():
        parts.append("【已有摘要】\n" + previous_summary.strip())
    lines = [f"{message.role}：{message.content}" for message in overflow]
    parts.append("【需要压缩的对话】\n" + "\n".join(lines))
    return "\n\n".join(parts)


async def summarize_history(
    gateway: Summarizer,
    overflow: Sequence[ChatMessage],
    *,
    previous_summary: str = "",
    max_chars: int = 600,
    timeout_seconds: float | None = None,
) -> str:
    """把超出窗口的旧对话压缩为摘要；失败时退回原摘要（不阻断主流程）。

    Args:
        gateway: LLM 网关（复用同一降级链，避免另建 Provider）。
        overflow: 需要压缩的旧消息（按时间升序）。
        previous_summary: 已有摘要，与新对话合并压缩（增量）。
        max_chars: 摘要长度上限（字符）。
        timeout_seconds: **硬性总超时**（覆盖网关内部的重试与退避）——记忆维护属非关键
            路径，必须防止单次抖动长时间占用资源（最坏耗时 = providers × retries × timeout）。

    Returns:
        新摘要；生成失败/超时或无有效内容时返回 ``previous_summary``。
    """
    if not overflow:
        return previous_summary
    prompt = build_summary_prompt(overflow, previous_summary, max_chars)
    try:
        summary = await _generate_with_timeout(gateway, prompt, timeout_seconds)
    except Exception as exc:  # noqa: BLE001 - 摘要失败不得影响本轮问答
        logger.warning("会话摘要生成失败，沿用原摘要: %s", exc)
        return previous_summary
    text = (summary or "").strip()
    if not text:
        return previous_summary
    return text[:max_chars]


# ---------- 跨会话用户画像（M4-4.4） ----------

_PROFILE_INSTRUCTION = (
    "你是用户画像维护助手。请把给出的对话中**值得跨会话长期记住**的信息合并进已有画像："
    "用户偏好、身份/角色、长期约束、反复出现的诉求。"
    "**禁止**记录手机号、身份证、银行卡、邮箱、地址等具体个人信息，"
    "也**禁止**输出形如 [手机号1] 的占位符；信息冲突时以新信息为准；不确定则不写。"
    "只输出画像正文，不要解释、不要寒暄。"
)


def build_profile_prompt(previous_profile: str, overflow: Sequence[ChatMessage], max_chars: int) -> str:
    """拼装用户画像合并提示词（已有画像 + 本轮新增对话）。"""
    parts: list[str] = [_PROFILE_INSTRUCTION, f"画像请控制在 {max_chars} 字以内。"]
    parts.append("【已有画像】\n" + (previous_profile.strip() or "（暂无）"))
    lines = [f"{message.role}：{message.content}" for message in overflow]
    parts.append("【本轮对话】\n" + "\n".join(lines))
    return "\n\n".join(parts)


async def merge_profile(
    gateway: Summarizer,
    previous_profile: str,
    overflow: Sequence[ChatMessage],
    *,
    max_chars: int = 400,
    timeout_seconds: float | None = None,
) -> str:
    """把本轮对话要点合并进既有用户画像；失败时退回原画像（不阻断主流程）。

    Args:
        gateway: LLM 网关（复用同一降级链）。
        previous_profile: 已有画像（可能为空）。
        overflow: 本轮落到窗口之外的旧消息（按时间升序）。
        max_chars: 画像长度上限（字符）。
        timeout_seconds: 硬性总超时（与摘要同源，防非关键路径长时间占用资源）。

    Returns:
        新画像；生成失败/超时或无有效内容时返回 ``previous_profile``。
    """
    if not overflow:
        return previous_profile
    prompt = build_profile_prompt(previous_profile, overflow, max_chars)
    try:
        profile = await _generate_with_timeout(gateway, prompt, timeout_seconds)
    except Exception as exc:  # noqa: BLE001 - 画像合并失败不得影响本轮问答
        logger.warning("用户画像合并失败，沿用原画像: %s", exc)
        return previous_profile
    text = sanitize_profile(profile or "", max_chars=max_chars)
    return text or previous_profile


def sanitize_profile(text: str, *, mask_address: bool = True, max_chars: int = 400) -> str:
    """画像入库前的兜底净化：去占位符 → 抹除残余 PII（换为纯标签）→ 截断。

    跨会话无法还原 ``[..]`` 占位符（token_map 为会话级内存态），留存即泄漏给用户；
    因此画像一律「不存 PII、不存占位符」。
    """
    if not text:
        return ""
    cleaned = redact_pii(strip_placeholders(text), mask_address=mask_address)
    return cleaned.strip()[:max_chars]
