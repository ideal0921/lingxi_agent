"""知识库 RAG 统一中间表示。

数据流：解析器输出 ``StructuredDoc``（结构化文档），切分器消费它产出
``Chunk``（子块，256~512 token，向量化与召回单元）与 ``ParentChunk``（父块，
章节级 1k~2k token，供 LLM 生成回填）。

字段设计对齐《架构设计》7.4.6 的 Milvus Collection schema，保证切分产物
可直接映射入库，无需二次转换。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum


class DocType(StrEnum):
    """知识库支持的文档类型，决定解析分派与切分策略。"""

    PDF = "pdf"
    DOCX = "docx"
    PPTX = "pptx"
    SHEET = "sheet"  # Excel / CSV
    HTML = "html"
    MARKDOWN = "markdown"
    TEXT = "text"


class BlockKind(StrEnum):
    """结构块类型，用于识别结构边界。"""

    HEADING = "heading"  # 标题，章节切分边界
    PARAGRAPH = "paragraph"  # 正文段落
    TABLE_ROW = "table_row"  # 表格行（已语义化转写的单行文本）
    BULLET = "bullet"  # 要点 / 列表项
    CAPTION = "caption"  # 图表标题、页脚等附属文本


@dataclass(slots=True)
class Block:
    """解析产物中的最小结构块，是切分的输入单元。

    Attributes:
        text: 已规范化的文本内容（表格行此处为语义化转写后的自然语句）。
        level: 标题层级 1~6；非标题恒为 0，用于构建 heading_path。
        page: 页码（PDF / 扫描件），其余类型为 None。
        slide_no: 幻灯片页序（PPT）。
        sheet: 工作表名（Excel / CSV）。
        row_range: 表格行区间（如 ``"2-51"``），供表格溯源。
    """

    kind: BlockKind
    text: str
    level: int = 0
    page: int | None = None
    slide_no: int | None = None
    sheet: str | None = None
    row_range: str | None = None


@dataclass(slots=True)
class StructuredDoc:
    """结构化文档：解析器的统一输出，切分器的统一输入。"""

    doc_id: str
    doc_type: DocType
    title: str
    blocks: list[Block]
    tenant_id: str = ""
    kb_id: str = ""


@dataclass(slots=True)
class ParentChunk:
    """父块：章节级上下文，检索命中子块后按 ``parent_id`` 回填给 LLM。"""

    parent_id: str
    doc_id: str
    content: str
    heading_path: str
    seq: int
    tenant_id: str = ""
    kb_id: str = ""


@dataclass(slots=True)
class Chunk:
    """子块：向量化与召回的最小单元，写入 Milvus 子块 Collection。"""

    chunk_id: str
    doc_id: str
    kb_id: str
    tenant_id: str
    parent_id: str
    content: str
    seq: int
    heading_path: str
    doc_type: DocType
    page: int | None = None
    meta: dict[str, str] = field(default_factory=dict)


@dataclass(slots=True)
class SplitResult:
    """切分结果：子块与父块成对产出，入库时一并写入 Milvus。"""

    children: list[Chunk]
    parents: list[ParentChunk]
