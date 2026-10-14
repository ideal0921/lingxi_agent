"""中文友好的 token 估算与句界切分。

不依赖第三方分词器，保证入库管道可离线运行：
- **token 估算**：CJK 字符按 1 字 ≈ 1 token，其余按约 3.5 字符 ≈ 1 token 折算。
  仅用于切分预算控制，与真实模型分词存在误差，不影响切分正确性。
- **句界切分**：按中英文句末标点与换行切分句子，**保证句子不被截断**
  （超长段落必须按句界拆，绝不在句子中间切断语义）。
"""

from __future__ import annotations

import re

# 中文句末标点：出现即视为句子边界（其后无需空格）
_CJK_SENTENCE_END = "。！？；…"
# 英文句末标点：仅当其后紧跟空白且下一字符非数字时才视为边界，
# 避免误切小数（3.14）、版本号（v1.2）等
_ASCII_SENTENCE_END = ".!?;"
_ASCII_CHARS_PER_TOKEN = 3.5

# 预编译：CJK 字符（含中日韩统一表意文字与全角标点）
_CJK_PATTERN = re.compile(r"[\u3000-\u9fff\uff00-\uffef]")
# 预编译：以中英文句末标点打断句子（保留标点）
_SENTENCE_SPLIT_PATTERN = re.compile(rf"(?<=[{_CJK_SENTENCE_END}])|(?<=[{_ASCII_SENTENCE_END}])(?=\s)")


def count_tokens(text: str) -> int:
    """估算文本 token 数（向上取整）。

    Args:
        text: 待估算文本。

    Returns:
        估算的 token 数；空文本返回 0。
    """
    if not text:
        return 0
    cjk_count = len(_CJK_PATTERN.findall(text))
    ascii_count = len(text) - cjk_count
    tokens = cjk_count + ascii_count / _ASCII_CHARS_PER_TOKEN
    return int(tokens) + (1 if tokens % 1 else 0)


def split_sentences(text: str) -> list[str]:
    """按句界把文本拆成句子列表，保留句末标点。

    换行先作为强制边界（标题、列表项常以换行分隔），再在行内按句末标点切分。

    Args:
        text: 待切分文本。

    Returns:
        句子列表（已去除首尾空白，空句被丢弃）。
    """
    sentences: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        # 行内按句末标点切分；被切出的碎片（无句末标点的残句）同样保留
        sentences.extend(part.strip() for part in _SENTENCE_SPLIT_PATTERN.split(stripped) if part.strip())
    return sentences
