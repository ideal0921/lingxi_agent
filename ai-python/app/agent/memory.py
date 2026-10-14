"""长程记忆摘要（M3-3.11）：把超出上下文窗口的旧对话压缩为要点摘要。

定位（《架构设计》7.5 分层记忆）：会话上下文 = **长程摘要 + 最近 N 轮原文 + 本轮检索知识**。
本模块只负责「把旧消息压缩成摘要」；摘要的存放与回传由 Java 会话域完成
（经 ``lingxi.session.summary`` 事件回写，随下一轮事件带回），因此进程重启不丢记忆。

实现要点：
- **增量压缩**：入参包含「已有摘要」，新旧合并后再压缩，避免每次重算整段历史；
- **失败不阻断**：摘要生成失败（模型不可用等）时返回原摘要，不影响本轮应答；
- **长度受控**：摘要按配置上限截断，避免随事件无限膨胀。
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import Protocol

from app.agent.state import ChatMessage

logger = logging.getLogger(__name__)


class Summarizer(Protocol):
    """摘要所需的 LLM 能力（与 :class:`app.gateway.router.LLMGateway` 形态一致）。"""

    async def generate(self, prompt: str, model: str | None = None) -> str: ...


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
) -> str:
    """把超出窗口的旧对话压缩为摘要；失败时退回原摘要（不阻断主流程）。

    Args:
        gateway: LLM 网关（复用同一降级链，避免另建 Provider）。
        overflow: 需要压缩的旧消息（按时间升序）。
        previous_summary: 已有摘要，与新对话合并压缩（增量）。
        max_chars: 摘要长度上限（字符）。

    Returns:
        新摘要；生成失败或无有效内容时返回 ``previous_summary``。
    """
    if not overflow:
        return previous_summary
    prompt = build_summary_prompt(overflow, previous_summary, max_chars)
    try:
        summary = await gateway.generate(prompt)
    except Exception as exc:  # noqa: BLE001 - 摘要失败不得影响本轮问答
        logger.warning("会话摘要生成失败，沿用原摘要: %s", exc)
        return previous_summary
    text = (summary or "").strip()
    if not text:
        return previous_summary
    return text[:max_chars]
