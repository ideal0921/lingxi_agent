"""文档解析器单测。

零依赖解析器（Markdown / 纯文本 / CSV / HTML）为必测项；重依赖解析器
（Word / PPT / Excel）通过 ``importorskip`` 在有库时验证，缺失时跳过，
保证 CI 在最小依赖环境下仍能跑通核心切分链路。PDF 的标题启发式与文本分组
为纯函数，直接单测（不依赖 pdfplumber）。
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.rag.ingest.parser import parse_document, supported_suffixes
from app.rag.ingest.parser.pdf import _looks_like_heading, _text_blocks
from app.rag.models import BlockKind, DocType


class TestDispatch:
    def test_supported_suffixes_cover_all_types(self) -> None:
        assert {
            ".md",
            ".txt",
            ".html",
            ".docx",
            ".pptx",
            ".xlsx",
            ".xlsm",
            ".csv",
            ".pdf",
        } <= supported_suffixes()

    def test_unsupported_suffix_raises(self) -> None:
        with pytest.raises(ValueError, match="不支持的文档类型"):
            parse_document("unknown.xyz", "doc-x")


class TestMarkdownParser:
    def test_heading_and_paragraph(self, tmp_path: Path) -> None:
        path = tmp_path / "policy.md"
        path.write_text("# 售后政策\n\n政策说明文字。\n", encoding="utf-8")

        doc = parse_document(path, "doc-md")
        assert doc.doc_type is DocType.MARKDOWN
        assert doc.title == "售后政策"
        assert [block.kind for block in doc.blocks] == [BlockKind.HEADING, BlockKind.PARAGRAPH]
        assert doc.blocks[0].level == 1

    def test_table_rows_are_semantic_not_cellwise(self, tmp_path: Path) -> None:
        """表格必须按行转写为「列名：值」，而非按单元格切分。"""
        path = tmp_path / "goods.md"
        path.write_text(
            "| 型号 | 价格 | 库存 |\n| --- | --- | --- |\n| X1 | 399 | 20 |\n| X2 | 499 | 0 |\n",
            encoding="utf-8",
        )

        doc = parse_document(path, "doc-md-table")
        rows = [block for block in doc.blocks if block.kind is BlockKind.TABLE_ROW]
        assert len(rows) == 2
        assert rows[0].text == "型号：X1；价格：399；库存：20"
        assert rows[1].text == "型号：X2；价格：499；库存：0"
        assert rows[0].row_range == "1-1"

    def test_list_items_become_bullets(self, tmp_path: Path) -> None:
        path = tmp_path / "tips.md"
        path.write_text("- 要点一\n- 要点二\n", encoding="utf-8")
        doc = parse_document(path, "doc-md-list")
        assert all(block.kind is BlockKind.BULLET for block in doc.blocks)


class TestTextParser:
    def test_each_line_is_paragraph(self, tmp_path: Path) -> None:
        path = tmp_path / "log.txt"
        path.write_text("第一行内容\n\n第二行内容\n", encoding="utf-8")
        doc = parse_document(path, "doc-txt")
        assert doc.doc_type is DocType.TEXT
        assert [block.text for block in doc.blocks] == ["第一行内容", "第二行内容"]

    def test_gbk_fallback(self, tmp_path: Path) -> None:
        """非 UTF-8 中文文件应能回退解码，不中断入库。"""
        path = tmp_path / "gbk.txt"
        path.write_bytes("中文内容测试\n".encode("gbk"))
        doc = parse_document(path, "doc-gbk")
        assert doc.blocks[0].text == "中文内容测试"


class TestCsvParser:
    def test_csv_header_rendering(self, tmp_path: Path) -> None:
        path = tmp_path / "goods.csv"
        path.write_text("型号,价格,库存\nX1,399,20\n", encoding="utf-8")

        doc = parse_document(path, "doc-csv")
        assert doc.doc_type is DocType.SHEET
        assert doc.blocks[0].kind is BlockKind.TABLE_ROW
        assert doc.blocks[0].text == "型号：X1；价格：399；库存：20"
        assert doc.blocks[0].sheet == "goods"
        assert doc.blocks[0].row_range == "2-2"

    def test_csv_empty_cells_skipped(self, tmp_path: Path) -> None:
        path = tmp_path / "partial.csv"
        path.write_text("型号,价格,备注\nX1,,无\n", encoding="utf-8")
        doc = parse_document(path, "doc-csv-2")
        assert doc.blocks[0].text == "型号：X1；备注：无"


class TestHtmlParser:
    def test_blocks_and_script_stripped(self, tmp_path: Path) -> None:
        path = tmp_path / "page.html"
        path.write_text(
            "<html><head><style>p{color:red}</style></head><body>"
            "<h1>售后政策</h1><p>政策说明。</p><ul><li>要点一</li></ul>"
            "<table><tr><th>型号</th><th>价格</th></tr><tr><td>X1</td><td>399</td></tr></table>"
            "<script>var secret = 1;</script></body></html>",
            encoding="utf-8",
        )

        doc = parse_document(path, "doc-html")
        assert doc.doc_type is DocType.HTML
        assert doc.title == "售后政策"
        assert any(b.kind is BlockKind.HEADING and b.text == "售后政策" for b in doc.blocks)
        assert any(b.kind is BlockKind.PARAGRAPH and b.text == "政策说明。" for b in doc.blocks)
        assert any(b.kind is BlockKind.BULLET and b.text == "要点一" for b in doc.blocks)
        assert any(b.kind is BlockKind.TABLE_ROW and b.text == "型号：X1；价格：399" for b in doc.blocks)
        # script/style 内容不得进入知识库
        assert not any("secret" in b.text or "color:red" in b.text for b in doc.blocks)


class TestPdfHeuristics:
    def test_heading_detection(self) -> None:
        assert _looks_like_heading("第三章 售后政策")
        assert _looks_like_heading("3.1 退换货流程")
        assert _looks_like_heading("一、总则")
        assert not _looks_like_heading("这条描述以句号结尾，是正文内容。")

    def test_text_blocks_grouping(self) -> None:
        text = "第一章 概述\n\n这是第一段内容。\n这是第二句。\n\n3.1 细则\n说明文字。"
        blocks = _text_blocks(text, page_no=2)
        assert blocks[0].kind is BlockKind.HEADING and blocks[0].page == 2
        # 连续正文行合并为同一段落块
        paragraph = next(b for b in blocks if b.kind is BlockKind.PARAGRAPH)
        assert paragraph.text == "这是第一段内容。\n这是第二句。"


class TestPdfParser:
    """PDF 端到端：用 reportlab 生成中文样本（含标题/段落/表格）后解析。"""

    @staticmethod
    def _build_sample(path: Path) -> None:
        from reportlab.lib import colors
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import ParagraphStyle
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        from reportlab.platypus import Paragraph, SimpleDocTemplate, Table, TableStyle

        pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
        style = ParagraphStyle("cn", fontName="STSong-Light", fontSize=12)
        document = SimpleDocTemplate(str(path), pagesize=A4)
        table = Table([["型号", "价格"], ["X1", "399"]])
        # 带框线表格：pdfplumber 默认按线框识别表格
        table.setStyle(
            TableStyle(
                [
                    ("FONTNAME", (0, 0), (-1, -1), "STSong-Light"),
                    ("GRID", (0, 0), (-1, -1), 0.5, colors.black),
                ]
            )
        )
        document.build(
            [
                Paragraph("第一章 售后政策", style),
                Paragraph("这是政策说明的正文内容，描述退换货流程。", style),
                table,
            ]
        )

    def test_parse_pdf(self, tmp_path: Path) -> None:
        pytest.importorskip("reportlab")
        pytest.importorskip("pdfplumber")
        path = tmp_path / "policy.pdf"
        self._build_sample(path)

        doc = parse_document(path, "doc-pdf")
        assert doc.doc_type is DocType.PDF
        assert any(b.kind is BlockKind.HEADING and "售后政策" in b.text for b in doc.blocks)
        texts = "".join(b.text for b in doc.blocks)
        assert "退换货流程" in texts
        # 表格按行语义化转写（页码写入溯源）
        assert any(b.kind is BlockKind.TABLE_ROW and "X1" in b.text for b in doc.blocks)


class TestOfficeParsers:
    def test_xlsx(self, tmp_path: Path) -> None:
        openpyxl = pytest.importorskip("openpyxl")
        workbook = openpyxl.Workbook()
        sheet = workbook.active
        sheet.title = "商品表"
        sheet.append(["型号", "价格"])
        sheet.append(["X1", 399])
        path = tmp_path / "goods.xlsx"
        workbook.save(path)

        doc = parse_document(path, "doc-xlsx")
        assert doc.blocks[0].kind is BlockKind.HEADING
        assert doc.blocks[0].text == "商品表"
        assert doc.blocks[1].text == "型号：X1；价格：399"

    def test_docx(self, tmp_path: Path) -> None:
        docx = pytest.importorskip("docx")
        document = docx.Document()
        document.add_heading("售后政策", level=1)
        document.add_paragraph("政策说明。")
        table = document.add_table(rows=2, cols=2)
        table.cell(0, 0).text, table.cell(0, 1).text = "型号", "价格"
        table.cell(1, 0).text, table.cell(1, 1).text = "X1", "399"
        path = tmp_path / "policy.docx"
        document.save(path)

        doc = parse_document(path, "doc-docx")
        assert doc.blocks[0].kind is BlockKind.HEADING and doc.blocks[0].level == 1
        assert any(b.kind is BlockKind.TABLE_ROW and b.text == "型号：X1；价格：399" for b in doc.blocks)

    def test_pptx(self, tmp_path: Path) -> None:
        pptx = pytest.importorskip("pptx")
        presentation = pptx.Presentation()
        slide = presentation.slides.add_slide(presentation.slide_layouts[1])
        slide.shapes.title.text = "售后政策"
        slide.placeholders[1].text = "要点一"
        path = tmp_path / "policy.pptx"
        presentation.save(path)

        doc = parse_document(path, "doc-pptx")
        assert doc.title == "售后政策"
        assert any(b.kind is BlockKind.HEADING and b.slide_no == 1 for b in doc.blocks)
        assert any(b.kind is BlockKind.BULLET and b.text == "要点一" for b in doc.blocks)
