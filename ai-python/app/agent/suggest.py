"""坐席快捷回复建议生成（M4-4.6）：提示词拼装、候选解析与并发闸门。

定位：坐席在工作台点「快捷回复」时，经 **Go → Java → 本服务（gRPC 同步）** 请求 AI 依
会话上下文生成 1~3 条**回复建议草稿**，供坐席选择/编辑后再发送；AI **不直接对客发送**。

设计要点：
- **不走知识检索**：同步路径要求低延迟，只用「会话摘要 + 近 N 轮 + 坐席意图」，避免引入
  Milvus / 云端 embedding 的网络往返；
- **PII 脱敏**：进模型前对摘要/历史/意图统一脱敏，返回候选前还原（工程强制约束）；
- **并发闸门**（:class:`ConcurrencyGate`）：独立于对话主链路的并发上限；**默认不排队**
  （``queue_wait_seconds=0``），闸门满时立即降级为异步，避免把坐席的等待二次放大。
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from collections.abc import Sequence

from app.agent.state import ChatMessage
from app.privacy import PiiMasker

logger = logging.getLogger(__name__)

_SUGGEST_INSTRUCTION = (
    "你是资深客服的写作助手。请基于下面的会话上下文，生成 {count} 条**可直接发送给用户**的"
    "中文回复候选，供坐席挑选或修改后发送。要求：口吻专业、礼貌、简洁（每条不超过两句话）；"
    "仅依据上下文，不要编造未出现的事实（如订单号、金额、时效）；"
    "候选之间应有差异（例如：安抚型 / 说明型 / 追问型）。\n"
    "输出格式：每行一条候选，**不要编号、不要解释、不要 Markdown**。"
)

# 行首编号（如 "1. " / "1、 " / "- " / "•"）——模型偶尔仍会加，解析时去掉
_LEADING_MARKER = re.compile(r"^\s*(?:[-*•·]\s*|\d+\s*[.、)．]\s*)")


def build_suggest_prompt(
    summary: str,
    history: Sequence[ChatMessage],
    intent: str = "",
    max_suggestions: int = 3,
) -> str:
    """拼装建议生成提示词：人设与输出格式 → 会话摘要 → 近 N 轮 → 坐席意图。"""
    parts: list[str] = [_SUGGEST_INSTRUCTION.format(count=max(1, max_suggestions))]
    if (summary or "").strip():
        parts.append("【会话摘要】\n" + summary.strip())
    if history:
        lines = [f"{message.role}：{message.content}" for message in history]
        parts.append("【近期对话】\n" + "\n".join(lines))
    else:
        parts.append("【近期对话】\n（暂无）")
    if (intent or "").strip():
        parts.append("【坐席补充意图】\n" + intent.strip())
    return "\n\n".join(parts)


def parse_candidates(text: str, max_candidates: int = 3) -> list[str]:
    """把模型输出解析为候选列表：去编号/空行、去重、截断到上限。

    模型偶尔不按格式输出（带编号、带解释行），此处做宽松解析，保证「能返回候选」
    优于「严格格式导致空结果」。
    """
    if not text:
        return []
    candidates: list[str] = []
    seen: set[str] = set()
    for raw_line in text.splitlines():
        line = _LEADING_MARKER.sub("", raw_line).strip()
        if not line:
            continue
        if line in seen:
            continue
        seen.add(line)
        candidates.append(line)
        if len(candidates) >= max(1, max_candidates):
            break
    return candidates


def new_suggest_masker(*, enabled: bool, mask_address: bool) -> PiiMasker:
    """创建本请求专用的脱敏器（一次请求内占位符一致，返回前可完整还原）。"""
    return PiiMasker(enabled=enabled, mask_address=mask_address)


class ConcurrencyGate:
    """独立并发闸门（默认 32）：隔离同步通道，不挤压对话主链路。

    - ``acquire()`` 返回 ``False`` 表示**闸门已满**（调用方应立即降级为异步）；
    - ``queue_wait_seconds<=0`` 时**不等待**（默认），避免坐席等待被二次放大；
    - 暴露 ``snapshot()`` 供压测观测（在飞数 / 累计 / 降级 / 超时），便于证明「不泄漏」。
    """

    def __init__(self, *, limit: int = 32, queue_wait_seconds: float = 0.0) -> None:
        self._sem = asyncio.Semaphore(max(1, limit))
        self._queue_wait = queue_wait_seconds
        self._limit = max(1, limit)
        self._in_flight = 0
        self._total = 0
        self._busy = 0
        self._timeouts = 0

    async def acquire(self) -> bool:
        """尝试进入闸门；满则立即返回 False（不排队）。"""
        if self._queue_wait <= 0:
            if self._sem.locked():
                self._busy += 1
                return False
            await self._sem.acquire()  # 单事件循环内：check 与 acquire 之间无 await，无竞态
        else:
            try:
                await asyncio.wait_for(self._sem.acquire(), timeout=self._queue_wait)
            except TimeoutError:
                self._busy += 1
                return False
        self._in_flight += 1
        self._total += 1
        return True

    def release(self) -> None:
        self._in_flight = max(0, self._in_flight - 1)
        self._sem.release()

    def mark_timeout(self) -> None:
        """记录一次生成超时（供统计）。"""
        self._timeouts += 1

    @property
    def limit(self) -> int:
        return self._limit

    def snapshot(self) -> dict[str, int]:
        """当前统计（压测观察点：``in_flight`` 应能回落到 0）。"""
        return {
            "limit": self._limit,
            "in_flight": self._in_flight,
            "total": self._total,
            "busy_degraded": self._busy,
            "timeouts": self._timeouts,
        }


def elapsed_ms(started: float) -> int:
    """本请求耗时（毫秒，供响应观测）。"""
    return int((time.perf_counter() - started) * 1000)
