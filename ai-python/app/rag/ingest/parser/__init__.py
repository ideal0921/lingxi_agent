"""文档解析分派：按文件扩展名路由到对应解析器。

所有解析器统一签名 ``parse(path, doc_id, *, tenant_id, kb_id) -> StructuredDoc``，
重依赖由各解析器惰性导入，故本模块导入不会触发任何第三方库加载。
"""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from app.rag.ingest.parser import docx, html, pdf, pptx, sheet, text
from app.rag.models import StructuredDoc

ParserFn = Callable[..., StructuredDoc]

_SUFFIX_PARSERS: dict[str, ParserFn] = {
    ".md": text.parse_markdown,
    ".markdown": text.parse_markdown,
    ".txt": text.parse_text,
    ".htm": html.parse_html,
    ".html": html.parse_html,
    ".docx": docx.parse_docx,
    ".pptx": pptx.parse_pptx,
    ".xlsx": sheet.parse_xlsx,
    ".xlsm": sheet.parse_xlsx,
    ".csv": sheet.parse_csv,
    ".pdf": pdf.parse_pdf,
}


def supported_suffixes() -> frozenset[str]:
    """返回当前已登记的文档扩展名集合。"""
    return frozenset(_SUFFIX_PARSERS)


def parse_document(
    path: str | Path, doc_id: str, *, tenant_id: str = "", kb_id: str = ""
) -> StructuredDoc:
    """按扩展名解析文档为结构化文档。

    Args:
        path: 文档路径（扩展名决定解析器）。
        doc_id: 文档唯一标识，透传至切分产物。
        tenant_id: 租户标识，用于多租户隔离。
        kb_id: 知识库标识。

    Returns:
        :class:`StructuredDoc`。

    Raises:
        ValueError: 扩展名未登记时抛出，便于上游给出明确的上传校验提示。
    """
    file_path = Path(path)
    parser = _SUFFIX_PARSERS.get(file_path.suffix.lower())
    if parser is None:
        raise ValueError(f"不支持的文档类型: {file_path.suffix or file_path.name}")
    return parser(file_path, doc_id, tenant_id=tenant_id, kb_id=kb_id)
