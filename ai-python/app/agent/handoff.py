"""人工接管触发规则与生成中断标记（M4-4.5）。

职责边界：

- **触发规则**（:func:`decide_handoff`）：纯函数判定「低置信度 / 用户明示 / 情绪」三类
  规则，命中即由调用方**跳过 AI 生成**、改发 ``lingxi.ai.handoff`` 通知 Java 转人工；
  无 IO、无 LLM，可完全离线单测。
- **生成中断标记**（:class:`InterruptRegistry`）：由接管通知消费协程写入（Java 坐席接管 /
  AI 转人工成功后发布 ``lingxi.session.takeover``），生成循环**每片段读取**；命中即停止
  产出、发 ``interrupted`` 终帧、**不落库半截回答**。

规则优先级：**用户明示 > 情绪 > 低置信度**（先命中先返回）。

低置信度的稳妥做法：①**检索命中为空**（与分数量纲无关，默认启用）；②精排分低于阈值
（默认关闭）——精排分与 RRF 融合分**量纲不同不可比**，故仅在确实启用云端精排时才有意义。
"""

from __future__ import annotations

import logging
import re
import time
from collections.abc import Sequence
from dataclasses import dataclass

logger = logging.getLogger(__name__)

# 触发原因（随 handoff 事件下发给 Java，便于统计与坐席侧展示）
REASON_USER_REQUEST = "user_request"
REASON_EMOTION = "emotion"
REASON_LOW_CONFIDENCE = "low_confidence"


@dataclass(slots=True, frozen=True)
class HandoffDecision:
    """转人工判定结果。"""

    handoff: bool
    reason: str = ""


def _normalize(text: str) -> str:
    """归一化：去空白 + 转小写（中英文关键词匹配前统一处理，避免空格/大小写干扰）。"""
    return re.sub(r"\s+", "", text or "").lower()


def _split_keywords(raw: str) -> tuple[str, ...]:
    """把逗号分隔的关键词配置拆为元组（忽略空项）。"""
    return tuple(item.strip().lower() for item in (raw or "").split(",") if item.strip())


def decide_handoff(
    query: str,
    hit_scores: Sequence[float],
    *,
    user_keywords: str = "",
    emotion_keywords: str = "",
    emotion_min_hits: int = 2,
    empty_hits_enabled: bool = True,
    low_score_enabled: bool = False,
    low_score_threshold: float = 0.3,
) -> HandoffDecision:
    """判定是否应转人工（不命中返回 ``HandoffDecision(False, "")``）。

    Args:
        query: 用户本轮问题原文。
        hit_scores: 本轮检索命中分数（精排分或 RRF 分）；空序列表示**未检索到知识**。
        user_keywords: 用户明示转人工的关键词（逗号分隔）。
        emotion_keywords: 情绪类关键词（逗号分隔）。
        emotion_min_hits: 情绪关键词命中个数阈值（防单词误伤）。
        empty_hits_enabled: 是否把「检索命中为空」视为低置信度。
        low_score_enabled: 是否启用「最高分低于阈值」判定（默认关闭，见模块说明）。
        low_score_threshold: 最高分阈值（仅在 ``low_score_enabled`` 时生效）。
    """
    normalized = _normalize(query)
    if normalized:
        # 1) 用户明示转人工：优先且最确定
        for keyword in _split_keywords(user_keywords):
            if keyword and keyword in normalized:
                return HandoffDecision(True, REASON_USER_REQUEST)
        # 2) 情绪（投诉/维权等）：需命中多个关键词，避免单次误伤
        hits = sum(1 for keyword in _split_keywords(emotion_keywords) if keyword and keyword in normalized)
        if hits >= max(1, emotion_min_hits):
            return HandoffDecision(True, REASON_EMOTION)
    # 3) 低置信度：①检索命中为空 ②最高分低于阈值（需显式开启，量纲须可比）
    if not hit_scores:
        if empty_hits_enabled:
            return HandoffDecision(True, REASON_LOW_CONFIDENCE)
        return HandoffDecision(False, "")
    if low_score_enabled and max(hit_scores) < low_score_threshold:
        return HandoffDecision(True, REASON_LOW_CONFIDENCE)
    return HandoffDecision(False, "")


class InterruptRegistry:
    """会话级「生成中断」标记集合（M4-4.5）。

    并发说明：纯内存结构，读写均发生在**同一事件循环**内（生成协程与接管消费协程
    共享同一 loop），因而是原子的，**无需加锁**。

    生命周期：
    - 写入：收到接管通知事件；
    - 读取：生成循环每片段前 ``check()``（**只读不删**，同一轮可能多次检查）；
    - 清理：①同会话下一条消息开始处理时 :meth:`clear`（消解迟到的接管事件、避免误伤
      后续轮次）②TTL 惰性过期（防长期不活跃会话的标记堆积）。
    """

    def __init__(self, *, ttl_seconds: float = 300.0) -> None:
        self._ttl = ttl_seconds
        self._flags: dict[str, tuple[str, float]] = {}

    def mark(self, session_id: str, reason: str) -> None:
        """标记会话为「已中断」（携带原因）。"""
        if session_id:
            self._flags[session_id] = (reason, time.monotonic() + self._ttl)

    def check(self, session_id: str) -> str:
        """返回中断原因；未中断或已过期返回空串（过期项顺带清理）。"""
        item = self._flags.get(session_id)
        if item is None:
            return ""
        reason, expire_at = item
        if expire_at <= time.monotonic():
            self._flags.pop(session_id, None)
            return ""
        return reason

    def clear(self, session_id: str) -> None:
        """清除某会话的中断标记（新一轮消息开始处理时调用）。"""
        self._flags.pop(session_id, None)
