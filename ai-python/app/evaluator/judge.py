"""规则判定（M3-3.10）：Golden Set 用例的确定性打分。

判定维度（全部为规则，零额外成本、可进 CI）：

1. **关键事实**：``must_contain``（AND）与 ``any_of``（OR，容忍同义改写）是否出现；
2. **幻觉守卫**：``must_not_contain`` 是否被编造出来；
3. **拒答正确性**：知识不足的用例是否如实说明（而非编造），有明确期望事实的用例是否作答；
4. **检索命中**：引用标题是否含预期标题（作为指标记录，**不参与通过判定**——避免精排
   波动使回归结果不稳定）。

所有比较先做「去空白 + 小写」归一化，避免排版差异（如「3-7 个工作日」与「3-7个工作日」）
造成误判。
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from dataclasses import dataclass, field

from app.evaluator.models import GoldenCase

# 拒答/如实说明的常见表达（知识不足时模型应出现的措辞）
_REFUSAL_MARKERS: tuple[str, ...] = (
    "无法回答",
    "无法确认",
    "无法提供",
    "无法为您提供",
    "无法核实",
    "无法查询",
    "暂无",
    "没有相关",
    "没有关于",
    "没有提供",
    "没有提及",
    "没有找到",
    "未找到",
    "未提及",
    "未提供",
    "未包含",
    "不清楚",
    "不包含",
    "建议转人工",
    "转人工",
    "转接人工",
    "人工客服",
)

# 否定/存疑语境标记：关键词出现在这些词之后时，属「纠正/澄清/存疑」而非编造
_NEGATION_MARKERS: tuple[str, ...] = (
    "不是",
    "并非",
    "不支持",
    "不提供",
    "没有",
    "未",
    "不得",
    "不可",
    "不属于",
    "是否",
    "能否",
    "无法",
    "不确定",
    "未知",
    "不详",
)

# 后置反驳标记：关键词出现在这些小段**之后**时，同样属「纠正/澄清」。
# 中文否定既可前置（「不是 15 天无理由」）也可后置（「“15 天无理由退款”不成立」），
# 只回看前缀会漏掉后者，导致「正确纠正了用户错误前提」的回答被误判为编造。
# 这里刻意只收「反驳谓语」的强标记（不收「不是/并非」等前置型），避免把
# 「15 天无理由是标准做法，不是 7 天」这类真实编造放过。
_REFUTATION_MARKERS: tuple[str, ...] = (
    "不成立",
    "不对",
    "不正确",
    "不实",
    "有误",
    "是错的",
    "错误",
    "不适用",
    "不符合",
    "不支持",
    "不提供",
    "不属于",
)

# 句内回看窗口上限（归一化后字符数）：从所在句子的开头回看，避免跨句误判
_NEGATION_WINDOW_MAX = 40
# 句内前瞻窗口上限（归一化后字符数）：只覆盖关键词后紧邻的一小段，避免后续无关语句的否定被误用
_REFUTATION_WINDOW_MAX = 12
# 句子分隔符（用于把回看范围限制在关键词所在句内）
_SENTENCE_DELIMITERS = "。！？；\n.!?;"

_WHITESPACE = re.compile(r"\s+")


def normalize(text: str) -> str:
    """归一化：去除全部空白并转小写（中英文混排与排版差异鲁棒）。"""
    return _WHITESPACE.sub("", text or "").lower()


def is_refusal(answer: str) -> bool:
    """回答是否为「如实说明/建议转人工」的形态（仅用于记录指标）。"""
    normalized = normalize(answer)
    return any(normalize(marker) in normalized for marker in _REFUSAL_MARKERS)


def _negated_context(normalized: str, index: int) -> bool:
    """关键词出现处之前是否为否定/存疑语境。

    回看范围限制在**关键词所在句内**（从上一个句子分隔符到关键词），既能识别
    「不是 15 天无理由」「无法确认是否为终身免费」，又能避免跨句标记误判。
    """
    sentence_start = 0
    for position in range(index - 1, -1, -1):
        if normalized[position] in _SENTENCE_DELIMITERS:
            sentence_start = position + 1
            break
    window = normalized[sentence_start:index][-_NEGATION_WINDOW_MAX:]
    return any(marker in window for marker in _NEGATION_MARKERS)


def _refuted_context(normalized: str, keyword_end: int) -> bool:
    """关键词**之后**是否为反驳/纠正语境（与 :func:`_negated_context` 互补）。

    前瞻范围同样限制在**关键词所在句内**，且只覆盖紧邻的一小段（``_REFUTATION_WINDOW_MAX``）：
    既识别「“15 天无理由退款”不成立」「该说法有误」这类后置纠正，
    又不会把后续无关语句里的否定（如「支持 15 天无理由。7 天不适用」的第二句）误当作豁免。
    """
    limit = min(keyword_end + _REFUTATION_WINDOW_MAX, len(normalized))
    for position in range(keyword_end, limit):
        if normalized[position] in _SENTENCE_DELIMITERS:
            limit = position
            break
    window = normalized[keyword_end:limit]
    return any(marker in window for marker in _REFUTATION_MARKERS)


def _find_forbidden(answer_normalized: str, keywords: list[str]) -> list[str]:
    """查找违反幻觉守卫的关键词；前置否定或后置反驳语境（纠正/澄清）不计入违反。"""
    violations: list[str] = []
    for keyword in keywords:
        target = normalize(keyword)
        if not target:
            continue
        index = answer_normalized.find(target)
        while index != -1:
            # 前置否定（「不是 15 天无理由」）与后置反驳（「…不成立」）都视为澄清而非编造
            if not _negated_context(answer_normalized, index) and not _refuted_context(
                answer_normalized, index + len(target)
            ):
                violations.append(keyword)
                break
            index = answer_normalized.find(target, index + 1)
    return violations


@dataclass(slots=True)
class JudgeOutcome:
    """判定结果。"""

    passed: bool
    reasons: list[str] = field(default_factory=list)
    missing: list[str] = field(default_factory=list)
    forbidden: list[str] = field(default_factory=list)
    refusal_observed: bool = False
    heading_hit: bool | None = None


def judge(case: GoldenCase, answer: str, references: Sequence[str]) -> JudgeOutcome:
    """按规则判定单条用例。"""
    expect = case.expect
    normalized_answer = normalize(answer)

    missing = [key for key in expect.must_contain if normalize(key) not in normalized_answer]
    reasons: list[str] = []

    if missing:
        reasons.append("缺少关键事实：" + "、".join(missing))
    if expect.any_of and not any(normalize(key) in normalized_answer for key in expect.any_of):
        reasons.append("候选表达均未出现：" + "、".join(expect.any_of))

    forbidden = _find_forbidden(normalized_answer, expect.must_not_contain)
    if forbidden:
        reasons.append("出现编造关键词：" + "、".join(forbidden))

    # 拒答仅对「知识不足」用例构成通过条件；有明确期望事实的用例只要事实齐备即为作答
    # （作答后附带「建议转人工」属正常收尾，不算拒答）。
    refusal_observed = is_refusal(answer)
    if expect.refusal and not refusal_observed:
        reasons.append("知识不足但未如实说明（疑似编造）")

    heading_hit: bool | None = None
    if expect.expect_heading:
        heading_hit = any(expect.expect_heading in (reference or "") for reference in references)

    return JudgeOutcome(
        passed=not reasons,
        reasons=reasons,
        missing=missing,
        forbidden=forbidden,
        refusal_observed=refusal_observed,
        heading_hit=heading_hit,
    )
