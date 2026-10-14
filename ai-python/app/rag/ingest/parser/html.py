"""HTML 解析器（纯标准库 ``html.parser`` 实现，零第三方依赖）。

按块级标签（h1~h6 / p / li / table）提取文本并还原结构；``script``、``style``
内容整体跳过。生产环境如需正文去噪（导航/页脚剥离），可在本模块内替换为
trafilatura，接口保持不变。
"""

from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path

from app.rag.ingest.parser.base import ParserDependencyError, table_rows_to_blocks
from app.rag.models import Block, BlockKind, DocType, StructuredDoc

_HEADING_TAGS = {f"h{level}": level for level in range(1, 7)}
# 段落级容器：其结束即 flush；div/section 等容器的嵌套会产生少量碎片块，
# 由切分器按 token 预算重新装箱，不影响最终产物
_PARAGRAPH_TAGS = {"p", "div", "section", "article", "blockquote", "pre"}
_SKIP_TAGS = {"script", "style", "noscript"}


class _BlockCollector(HTMLParser):
    """把 HTML 流式解析为结构块序列。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.blocks: list[Block] = []
        self._buffer: list[str] = []
        self._current: tuple[BlockKind, int] | None = None
        self._skip_depth = 0
        self._table_rows: list[list[str]] = []
        self._row: list[str] = []
        self._in_table = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in _SKIP_TAGS:
            self._skip_depth += 1
            return
        if self._skip_depth:
            return
        if tag in _HEADING_TAGS:
            self._flush()
            self._current = (BlockKind.HEADING, _HEADING_TAGS[tag])
        elif tag == "li":
            self._flush()
            self._current = (BlockKind.BULLET, 0)
        elif tag in _PARAGRAPH_TAGS:
            self._flush()
            self._current = (BlockKind.PARAGRAPH, 0)
        elif tag == "table":
            self._flush()
            self._in_table = True
            self._table_rows = []
        elif tag == "tr" and self._in_table:
            self._row = []
        elif tag in ("td", "th") and self._in_table:
            self._row.append("")
        elif tag == "br":
            self._buffer.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in _SKIP_TAGS:
            self._skip_depth = max(0, self._skip_depth - 1)
            return
        if self._skip_depth:
            return
        if tag in ("td", "th") and self._in_table and self._row:
            self._row[-1] = self._pop_text()
        elif tag == "tr" and self._in_table and self._row:
            self._table_rows.append(self._row)
            self._row = []
        elif tag == "table" and self._in_table:
            self._in_table = False
            self._flush_table()
        elif tag in _HEADING_TAGS or tag in _PARAGRAPH_TAGS or tag == "li":
            self._flush()

    def handle_data(self, data: str) -> None:
        if self._skip_depth:
            return
        text = data.strip()
        if text:
            self._buffer.append(text)

    def _pop_text(self) -> str:
        text = " ".join(part for part in self._buffer if part)
        self._buffer.clear()
        return text

    def _flush(self) -> None:
        if self._current is None:
            self._buffer.clear()
            return
        text = self._pop_text()
        kind, level = self._current
        if text:
            self.blocks.append(Block(kind=kind, text=text, level=level))
        self._current = None

    def _flush_table(self) -> None:
        rows = [row for row in self._table_rows if any(cell.strip() for cell in row)]
        if not rows:
            return
        self.blocks.extend(table_rows_to_blocks(rows[0], rows[1:], start_row=1))


def parse_html(path: Path, doc_id: str, *, tenant_id: str = "", kb_id: str = "") -> StructuredDoc:
    """解析 HTML：提取标题/段落/列表/表格，剔除脚本与样式。"""
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        raise ParserDependencyError(f"读取 HTML 失败: {exc}") from exc

    collector = _BlockCollector()
    collector.feed(content)
    collector.close()
    collector._flush()  # 收尾未闭合的块

    blocks = collector.blocks
    title = next((b.text for b in blocks if b.kind is BlockKind.HEADING), path.stem)
    return StructuredDoc(
        doc_id=doc_id,
        doc_type=DocType.HTML,
        title=title,
        blocks=blocks,
        tenant_id=tenant_id,
        kb_id=kb_id,
    )
