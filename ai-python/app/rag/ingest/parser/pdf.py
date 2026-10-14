"""PDF 解析器：文本型 PDF 按页提取，表格按行语义化转写。

惰性导入 ``pdfplumber``。标题识别采用启发式（编号式标题、短行无句末标点），
生产环境可升级为基于字号聚类的版面分析；扫描件 OCR（PaddleOCR / RapidOCR）
为后续增量，接口保持不变。

已知局限：``extract_tables()`` 默认按线框识别表格，**无框线表格**（纯文本对齐
排版）不会被识别为表格，其内容会作为普通段落文本提取（语义不丢失，但缺少
行级「列名：值」转写）。如需覆盖无框线表格，可后续按 ``table_settings`` 的
text 策略做二次尝试，并配合置信度过滤，避免把普通文本误判为表格。
"""

from __future__ import annotations

import re
from pathlib import Path

from app.rag.ingest.parser.base import ParserDependencyError, table_rows_to_blocks
from app.rag.models import Block, BlockKind, DocType, StructuredDoc

# 常见编号式标题：第X章/节、1.2.3、一、二、…
_HEADING_PATTERNS = (
    re.compile(r"^第[一二三四五六七八九十百零\d]+[章节篇条]\s*"),
    re.compile(r"^\d+(?:\.\d+)*[\s、.]"),
    re.compile(r"^[一二三四五六七八九十]+[、.]"),
)
_SENTENCE_END = "。！？；.!?;"
# 短行（无句末标点）更可能是标题而非正文
_HEADING_MAX_CHARS = 30


def parse_pdf(path: Path, doc_id: str, *, tenant_id: str = "", kb_id: str = "") -> StructuredDoc:
    """解析文本型 PDF 为结构化文档（页码写入溯源元数据）。"""
    try:
        import pdfplumber
    except ImportError as exc:
        raise ParserDependencyError("解析 PDF 需要 pdfplumber，请执行 pip install pdfplumber") from exc

    blocks: list[Block] = []
    with pdfplumber.open(str(path)) as pdf:
        for page_no, page in enumerate(pdf.pages, start=1):
            for table in page.extract_tables() or []:
                rows = [
                    ["" if cell is None else str(cell).strip() for cell in row]
                    for row in table
                ]
                rows = [row for row in rows if any(cell for cell in row)]
                if rows:
                    blocks.extend(
                        table_rows_to_blocks(rows[0], rows[1:], page=page_no, start_row=1)
                    )
            blocks.extend(_text_blocks(page.extract_text() or "", page_no))

    title = next((b.text for b in blocks if b.kind is BlockKind.HEADING), path.stem)
    return StructuredDoc(
        doc_id=doc_id,
        doc_type=DocType.PDF,
        title=title,
        blocks=blocks,
        tenant_id=tenant_id,
        kb_id=kb_id,
    )


def _text_blocks(text: str, page_no: int) -> list[Block]:
    """把单页文本按行分组：标题行独立成块，连续正文行合并为段落块。"""
    blocks: list[Block] = []
    paragraph: list[str] = []

    def flush() -> None:
        if paragraph:
            blocks.append(Block(kind=BlockKind.PARAGRAPH, text="\n".join(paragraph), page=page_no))
            paragraph.clear()

    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            flush()
            continue
        if _looks_like_heading(stripped):
            flush()
            blocks.append(Block(kind=BlockKind.HEADING, text=stripped, level=1, page=page_no))
            continue
        paragraph.append(stripped)
    flush()
    return blocks


def _looks_like_heading(line: str) -> bool:
    """启发式判断某行是否为标题（编号式标题，或短行且无句末标点）。"""
    if any(pattern.match(line) for pattern in _HEADING_PATTERNS):
        return True
    return len(line) <= _HEADING_MAX_CHARS and not line.endswith(tuple(_SENTENCE_END))
