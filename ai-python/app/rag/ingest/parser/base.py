"""解析器公共设施：依赖缺失错误与表格语义化转写。

**表格转写原则**（《架构设计》7.4.5）：表格禁止按单元格切分（会彻底丢失语义），
需按「表头 → 行」做语义化转写，即把每行还原为「列名：值」的自然语句后再切分。
"""

from __future__ import annotations

from pathlib import Path

from app.rag.models import Block, BlockKind


class ParserDependencyError(RuntimeError):
    """解析某类型文档所需的第三方库缺失。

    用于惰性导入场景：解析器按需导入重依赖（如 python-docx / pdfplumber），
    缺失时抛出本异常并给出安装提示，避免拖垮整个入库管道。
    """


def read_text_with_fallback(path: Path) -> str:
    """按常见中文编码回退读取文本文件。

    依次尝试 UTF-8、UTF-8 BOM、GBK，全部失败时以 UTF-8 宽松模式兜底，
    避免单个乱码文件中断整批入库。
    """
    for encoding in ("utf-8", "utf-8-sig", "gbk"):
        try:
            return path.read_text(encoding=encoding)
        except UnicodeDecodeError:
            continue
    return path.read_text(encoding="utf-8", errors="replace")


def table_rows_to_blocks(
    header: list[str],
    rows: list[list[str]],
    *,
    sheet: str | None = None,
    page: int | None = None,
    slide_no: int | None = None,
    start_row: int = 1,
) -> list[Block]:
    """把二维表格按行语义化转写为结构块。

    每行生成一个 ``TABLE_ROW`` 块（行是切分与重叠的原子单元），文本形如
    ``型号：X1；价格：399；库存：20``。选择「列名：值」而非套用自然句式模板，
    是因为不同表的列语义各异，通用套模板易产生病句，而键值对形式无歧义且
    仍保留表头语义。

    Args:
        header: 表头单元格文本；为空时按 ``列1``、``列2`` 兜底。
        rows: 数据行，每行为单元格文本列表。
        sheet: 工作表名（Excel / CSV），写入溯源元数据。
        page: 页码（PDF 表格）。
        start_row: 数据行起始行号，用于生成 ``row_range``。

    Returns:
        ``TABLE_ROW`` 结构块列表，逐行对应。
    """
    blocks: list[Block] = []
    for offset, row in enumerate(rows):
        row_text = _render_row(header, row)
        if not row_text:
            continue
        row_no = start_row + offset
        blocks.append(
            Block(
                kind=BlockKind.TABLE_ROW,
                text=row_text,
                page=page,
                slide_no=slide_no,
                sheet=sheet,
                row_range=f"{row_no}-{row_no}",
            )
        )
    return blocks


def _render_row(header: list[str], row: list[str]) -> str:
    """把一行单元格转写为「列名：值」键值对（空单元格跳过）。"""
    cells: list[str] = []
    for index, value in enumerate(row):
        cell = "" if value is None else str(value).strip()
        if not cell:
            continue
        name = header[index].strip() if index < len(header) and header[index].strip() else f"列{index + 1}"
        cells.append(f"{name}：{cell}")
    return "；".join(cells)
