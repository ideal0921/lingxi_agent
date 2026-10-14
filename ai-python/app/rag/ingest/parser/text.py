"""Markdown 与纯文本解析器（纯标准库实现，零第三方依赖）。

Markdown 按 H1~H6 标题树递归解析，并识别列表、代码块与管道表格；
表格复用公共的「表头 → 行」语义化转写，避免按单元格切分丢失语义。
纯文本按行成段（空行仅作分隔），交由切分器按句界与 token 预算重组。
"""

from __future__ import annotations

import re
from pathlib import Path

from app.rag.ingest.parser.base import read_text_with_fallback, table_rows_to_blocks
from app.rag.models import Block, BlockKind, DocType, StructuredDoc

_HEADING_PATTERN = re.compile(r"^(#{1,6})\s+(.*)$")
_BULLET_PATTERN = re.compile(r"^([-*+]|\d+[.)])\s+(.*)$")
_FENCE_PATTERN = re.compile(r"^```")
_MD_TABLE_ROW_PATTERN = re.compile(r"^\|(.+)\|\s*$")
_MD_TABLE_SEP_PATTERN = re.compile(r"^\|[\s:|-]+\|\s*$")


def parse_markdown(
    path: Path, doc_id: str, *, tenant_id: str = "", kb_id: str = ""
) -> StructuredDoc:
    """解析 Markdown 为结构化文档（标题 / 列表 / 代码块 / 表格 / 段落）。"""
    lines = read_text_with_fallback(path).splitlines()
    blocks: list[Block] = []
    paragraph: list[str] = []
    in_fence = False
    index = 0

    def flush_paragraph() -> None:
        # 保留原始软换行（切分时按句界处理），避免中文段落被空格污染
        if paragraph:
            blocks.append(Block(kind=BlockKind.PARAGRAPH, text="\n".join(paragraph)))
            paragraph.clear()

    while index < len(lines):
        stripped = lines[index].strip()
        if _FENCE_PATTERN.match(stripped):
            flush_paragraph()
            in_fence = not in_fence
            index += 1
            continue
        if in_fence:
            paragraph.append(stripped)
            index += 1
            continue
        if not stripped:
            flush_paragraph()
            index += 1
            continue

        heading = _HEADING_PATTERN.match(stripped)
        if heading:
            flush_paragraph()
            blocks.append(
                Block(kind=BlockKind.HEADING, text=heading.group(2).strip(), level=len(heading.group(1)))
            )
            index += 1
            continue

        # 管道表格：当前行为表头，下一行为分隔行时整表按行转写
        if (
            _MD_TABLE_ROW_PATTERN.match(stripped)
            and index + 1 < len(lines)
            and _MD_TABLE_SEP_PATTERN.match(lines[index + 1].strip())
        ):
            flush_paragraph()
            header = _split_md_row(stripped)
            rows: list[list[str]] = []
            index += 2
            while index < len(lines) and _MD_TABLE_ROW_PATTERN.match(lines[index].strip()):
                rows.append(_split_md_row(lines[index].strip()))
                index += 1
            blocks.extend(table_rows_to_blocks(header, rows, start_row=1))
            continue

        bullet = _BULLET_PATTERN.match(stripped)
        if bullet:
            flush_paragraph()
            blocks.append(Block(kind=BlockKind.BULLET, text=bullet.group(2).strip()))
            index += 1
            continue

        paragraph.append(stripped)
        index += 1

    flush_paragraph()
    return _build_doc(doc_id, DocType.MARKDOWN, path, blocks, tenant_id, kb_id)


def parse_text(path: Path, doc_id: str, *, tenant_id: str = "", kb_id: str = "") -> StructuredDoc:
    """解析纯文本：每个非空行作为一个段落块。"""
    blocks = [
        Block(kind=BlockKind.PARAGRAPH, text=line.strip())
        for line in read_text_with_fallback(path).splitlines()
        if line.strip()
    ]
    return _build_doc(doc_id, DocType.TEXT, path, blocks, tenant_id, kb_id)


def _split_md_row(line: str) -> list[str]:
    """拆分 Markdown 管道表格行：去掉首尾竖线后按竖线切列。"""
    return [cell.strip() for cell in line.strip("|").split("|")]


def _build_doc(
    doc_id: str,
    doc_type: DocType,
    path: Path,
    blocks: list[Block],
    tenant_id: str,
    kb_id: str,
) -> StructuredDoc:
    """装配结构化文档，标题取首个标题块，缺省用文件名。"""
    title = next((b.text for b in blocks if b.kind is BlockKind.HEADING), path.stem)
    return StructuredDoc(
        doc_id=doc_id,
        doc_type=doc_type,
        title=title,
        blocks=blocks,
        tenant_id=tenant_id,
        kb_id=kb_id,
    )
