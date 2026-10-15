"""PII 脱敏：敏感信息进入云端模型（LLM / embedding）前替换为占位符，输出侧再还原。

设计要点（对应《工程设计与开发规划》3.9 的两项边界）：

- **占位符冲突**：占位符形如 ``[手机号1]``。生成占位符前会先把「原文中本就形如
  占位符的片段」纳入禁用集合，并逐序号递增直到不与原文冲突，确保占位符在原文中
  唯一——否则还原时会误改用户自己写的内容。
- **嵌套 / 重叠**：多类 PII 可能重叠（身份证 18 位数字同时像银行卡号；地址里包含
  手机号）。采用「**最长匹配优先 + 类型优先级 + 非重叠贪心**」选取，保证一段原文
  只被替换一次；地址整体替换后，其中包含的手机号也随之隐藏（不会被模型看到），
  还原时连同一并还原。
- **流式还原**：增量片段可能在占位符中间被切断。还原时按「最长可安全输出前缀」
  切分：仅当后缀不可能再拼成更长占位符时才输出，缓冲上限为占位符最大长度-1
  （通常 5~6 个字符），对首字延迟影响可忽略。

已知限制（保守优先，宁可多脱敏）：
- 地址为**启发式识别**（需含行政区划或街道等标记），难覆盖全部写法，且可能误伤
  含「路/街/巷」等词的业务文本；可用 ``pii_mask_address`` 关闭。
- 仅识别中国大陆手机号、18 位身份证、16~19 位银行卡、常规邮箱与中文地址。
"""

from __future__ import annotations

import logging
import re
from collections.abc import AsyncIterator
from dataclasses import dataclass
from enum import StrEnum

logger = logging.getLogger(__name__)


class PiiKind(StrEnum):
    """PII 类型。"""

    PHONE = "phone"
    ID_CARD = "id_card"
    BANK_CARD = "bank_card"
    EMAIL = "email"
    ADDRESS = "address"


# 占位符标签（中文可读，模型更易理解其含义）
_LABELS: dict[PiiKind, str] = {
    PiiKind.PHONE: "手机号",
    PiiKind.ID_CARD: "身份证",
    PiiKind.BANK_CARD: "银行卡",
    PiiKind.EMAIL: "邮箱",
    PiiKind.ADDRESS: "地址",
}

# 类型优先级（数字越小越强）：重叠时先按长度、再按优先级取舍
_PRIORITY: dict[PiiKind, int] = {
    PiiKind.ID_CARD: 0,
    PiiKind.BANK_CARD: 1,
    PiiKind.PHONE: 2,
    PiiKind.EMAIL: 3,
    PiiKind.ADDRESS: 4,
}

# 身份证：18 位（末位可为 X），前后不允许再粘数字/字母，避免截断更长数字串
_ID_CARD = re.compile(
    r"(?<![0-9A-Za-z])[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx](?![0-9A-Za-z])"
)
# 银行卡：16~19 位连续数字
_BANK_CARD = re.compile(r"(?<!\d)\d{16,19}(?!\d)")
# 手机号：中国大陆 11 位
_PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
# 邮箱
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
# 地址（启发式）：可选「省/市/区县」段 + 必需的「细节」标记（街道/路/号/栋/室…）
_ADDRESS = re.compile(
    r"(?:[\u4e00-\u9fa5]{2,8}(?:省|自治区|特别行政区))?"
    r"(?:[\u4e00-\u9fa5]{2,8}(?:市|自治州|地区))?"
    r"(?:[\u4e00-\u9fa5]{2,8}(?:区|县|旗))?"
    r"[\u4e00-\u9fa5A-Za-z0-9]{1,12}"
    r"(?:镇|乡|街道|路|街|巷|村|小区|大厦|广场|号楼|号|栋|单元|室|楼)"
)

# 地址有效性判定：包含数字，或包含行政区划/社区级标记（过滤「思路」「订单号」等误报）
_ADDRESS_ACCEPT_MARKERS = (
    "省", "自治区", "特别行政区", "市", "自治州", "地区", "区", "县", "旗",
    "镇", "乡", "街道", "村", "小区", "大厦", "广场",
)

# 形如占位符的片段（用于冲突检测）
_PLACEHOLDER_LIKE = re.compile(r"\[[^\[\]]{1,16}\]")

_PATTERNS: tuple[tuple[PiiKind, re.Pattern[str]], ...] = (
    (PiiKind.ID_CARD, _ID_CARD),
    (PiiKind.BANK_CARD, _BANK_CARD),
    (PiiKind.PHONE, _PHONE),
    (PiiKind.EMAIL, _EMAIL),
    (PiiKind.ADDRESS, _ADDRESS),
)


@dataclass(slots=True)
class PiiHit:
    """一处命中的 PII：原文区间与类型。"""

    start: int
    end: int
    kind: PiiKind

    @property
    def length(self) -> int:
        return self.end - self.start


def _detect(text: str, *, mask_address: bool) -> list[PiiHit]:
    """按类型扫描全部命中（未做重叠取舍）。"""
    hits: list[PiiHit] = []
    for kind, pattern in _PATTERNS:
        if kind is PiiKind.ADDRESS and not mask_address:
            continue
        for match in pattern.finditer(text):
            if kind is PiiKind.ADDRESS and not _is_address(match.group()):
                continue
            hits.append(PiiHit(start=match.start(), end=match.end(), kind=kind))
    return hits


def _is_address(candidate: str) -> bool:
    """地址启发式二次校验：必须有门牌/路号数字，或含行政区划/社区级标记。"""
    if any(char.isdigit() for char in candidate):
        return True
    return any(marker in candidate for marker in _ADDRESS_ACCEPT_MARKERS)


def _select(hits: list[PiiHit]) -> list[PiiHit]:
    """非重叠选取：最长优先，其次类型优先级，最后靠前优先。"""
    ordered = sorted(hits, key=lambda hit: (-hit.length, _PRIORITY[hit.kind], hit.start))
    selected: list[PiiHit] = []
    for hit in ordered:
        if any(not (hit.end <= kept.start or hit.start >= kept.end) for kept in selected):
            continue  # 与已选区间重叠
        selected.append(hit)
    return sorted(selected, key=lambda hit: hit.start)


def strip_placeholders(text: str) -> str:
    """移除文本中形如 ``[手机号1]`` 的占位符（跨会话长期记忆专用，M4-4.4）。

    占位符只在**产生它的那次会话**内可还原（``_token_map`` 为会话级内存态，进程重启
    即丢失）：跨会话写入画像时保留占位符毫无意义（还原不了），还会把字面量泄漏给
    用户。故画像入库前一律清除。
    """
    if not text:
        return ""
    return _PLACEHOLDER_LIKE.sub("", text)


def redact_pii(text: str, *, mask_address: bool = True) -> str:
    """把文本中的 PII 替换为**纯标签**（如「（手机号）」），不生成 ``[..]`` 形态占位符。

    用于跨会话长期记忆（M4-4.4）：画像需保留「用户有手机号 / 有收货地址」这类语义，
    但绝不能持久化具体值，也不能持久化无法跨会话还原的占位符。
    """
    if not text:
        return ""
    selected = _select(_detect(text, mask_address=mask_address))
    if not selected:
        return text
    parts: list[str] = []
    cursor = 0
    for hit in selected:
        parts.append(text[cursor:hit.start])
        parts.append(f"（{_LABELS[hit.kind]}）")
        cursor = hit.end
    parts.append(text[cursor:])
    return "".join(parts)


def _safe_emit_length(buffer: str, tokens: tuple[str, ...], max_len: int) -> int:
    """计算可安全输出的前缀长度。

    若 buffer 的某个后缀是**更长**占位符的前缀，则该后缀可能被后续片段补全，
    必须留在缓冲中；其余内容可立即输出（已完整的占位符由还原函数处理）。
    """
    limit = min(max_len - 1, len(buffer))
    for size in range(limit, 0, -1):
        suffix = buffer[-size:]
        if any(len(token) > size and token.startswith(suffix) for token in tokens):
            return len(buffer) - size
    return len(buffer)


class PiiMasker:
    """会话级脱敏器：同一实例内保证「同一原文 → 同一占位符」，可跨字段关联还原。

    一次对话/一次入库应共用同一实例：查询、历史、知识片段先后脱敏，回答/输出再
    统一还原，模型因此在同一占位符下保持语义一致。
    """

    def __init__(self, *, enabled: bool = True, mask_address: bool = True) -> None:
        self._enabled = enabled
        self._mask_address = mask_address
        self._token_map: dict[str, str] = {}
        self._counters: dict[PiiKind, int] = {}
        self._forbidden: set[str] = set()

    @property
    def token_map(self) -> dict[str, str]:
        """占位符 → 原文映射（只读副本，便于审计与还原校验）。"""
        return dict(self._token_map)

    def mask(self, text: str) -> str:
        """把文本中的 PII 替换为占位符；无命中时原样返回。"""
        if not self._enabled or not text:
            return text
        # 先记录原文中「本就形如占位符」的片段，避免新占位符与之冲突
        self._forbidden.update(_PLACEHOLDER_LIKE.findall(text))
        hits = _detect(text, mask_address=self._mask_address)
        if not hits:
            return text
        selected = _select(hits)
        parts: list[str] = []
        cursor = 0
        for hit in selected:
            parts.append(text[cursor:hit.start])
            parts.append(self._token_for(hit.kind, text[hit.start:hit.end]))
            cursor = hit.end
        parts.append(text[cursor:])
        return "".join(parts)

    def restore(self, text: str) -> str:
        """把占位符还原为原文。"""
        if not text or not self._token_map:
            return text
        for token, original in self._token_map.items():
            if token in text:
                text = text.replace(token, original)
        return text

    async def restore_stream(self, stream: AsyncIterator[str]) -> AsyncIterator[str]:
        """流式还原：缓冲可能未闭合的占位符，避免片段切断导致还原失败。"""
        if not self._token_map:
            async for part in stream:
                yield part
            return
        tokens = tuple(self._token_map)
        max_len = max(len(token) for token in tokens)
        buffer = ""
        async for part in stream:
            buffer += part
            emit_len = _safe_emit_length(buffer, tokens, max_len)
            if emit_len > 0:
                yield self.restore(buffer[:emit_len])
                buffer = buffer[emit_len:]
        if buffer:
            yield self.restore(buffer)

    def _token_for(self, kind: PiiKind, original: str) -> str:
        """分配占位符：同一原文复用同一占位符；与原文/已用占位符冲突则顺延序号。"""
        label = _LABELS[kind]
        prefix = f"[{label}"
        # 同一原文（同类型）跨字段复用同一占位符，保证模型在整轮对话中的指代一致
        for token, value in self._token_map.items():
            if value == original and token.startswith(prefix):
                return token

        index = self._counters.get(kind, 1)
        while index <= 10_000:
            token = f"[{label}{index}]"
            if token not in self._token_map and token not in self._forbidden:
                self._counters[kind] = index + 1
                self._token_map[token] = original
                return token
            index += 1
        logger.warning("PII 占位符序号耗尽，回退复用末位序号 kind=%s", kind)
        return f"{prefix}{index}]"
