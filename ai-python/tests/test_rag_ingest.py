"""知识入库管道单测：token 估算、句界切分与结构感知切分器。

覆盖 M3-3.3 验收要点：表格语义转写、跨页标题归属、超长段落句界切分
（句子不被截断）、父子块关联、相邻子块重叠。
"""

from __future__ import annotations

from app.rag.ingest.splitter import SplitConfig, split
from app.rag.ingest.tokenizer import count_tokens, split_sentences
from app.rag.models import Block, BlockKind, DocType, StructuredDoc


class TestTokenizer:
    def test_count_tokens_empty(self) -> None:
        assert count_tokens("") == 0

    def test_count_tokens_cjk_charwise(self) -> None:
        assert count_tokens("你好世界") == 4

    def test_split_sentences_keeps_decimal(self) -> None:
        # 小数点不应被误判为句界
        assert split_sentences("价格为 3.14 元。库存为 20 件。") == ["价格为 3.14 元。", "库存为 20 件。"]

    def test_split_sentences_english(self) -> None:
        assert split_sentences("Hello world. This is fine!") == ["Hello world.", "This is fine!"]

    def test_split_sentences_newline_is_boundary(self) -> None:
        assert split_sentences("第一行\n第二行") == ["第一行", "第二行"]


def _long_paragraph(sentence_count: int) -> str:
    """生成由指定数量短句构成的超长段落，用于句界切分测试。"""
    return "".join(f"这是第{index}个测试句子内容。" for index in range(sentence_count))


class TestSplitter:
    def test_multi_level_heading_path(self) -> None:
        """跨页/跨级标题归属：子块 heading_path 应还原多级标题路径。"""
        doc = StructuredDoc(
            doc_id="doc-1",
            doc_type=DocType.MARKDOWN,
            title="售后",
            blocks=[
                Block(kind=BlockKind.HEADING, text="售后政策", level=1),
                Block(kind=BlockKind.PARAGRAPH, text="政策总述。"),
                Block(kind=BlockKind.HEADING, text="退换货", level=2),
                Block(kind=BlockKind.PARAGRAPH, text="退换说明。"),
                Block(kind=BlockKind.HEADING, text="时效", level=3),
                Block(kind=BlockKind.PARAGRAPH, text="48 小时内处理。"),
            ],
        )
        result = split(doc)
        paths = {chunk.heading_path for chunk in result.children}
        assert paths == {"售后政策", "售后政策 > 退换货", "售后政策 > 退换货 > 时效"}

    def test_heading_without_body_is_skipped(self) -> None:
        """标题下无正文的章节不产出子块，但标题仍参与后续路径拼接。"""
        doc = StructuredDoc(
            doc_id="doc-2",
            doc_type=DocType.MARKDOWN,
            title="t",
            blocks=[
                Block(kind=BlockKind.HEADING, text="空章节", level=1),
                Block(kind=BlockKind.HEADING, text="有正文", level=1),
                Block(kind=BlockKind.PARAGRAPH, text="正文内容。"),
            ],
        )
        result = split(doc)
        assert len(result.children) == 1
        assert result.children[0].heading_path == "有正文"

    def test_long_paragraph_split_at_sentence_boundary(self) -> None:
        """超长段落必须按句界切分，句子不得被截断。"""
        source = _long_paragraph(40)
        doc = StructuredDoc(
            doc_id="doc-3",
            doc_type=DocType.TEXT,
            title="t",
            blocks=[Block(kind=BlockKind.PARAGRAPH, text=source)],
        )
        result = split(doc, SplitConfig(child_target_tokens=50, overlap_ratio=0.0))

        assert len(result.children) > 1, "超长段落应被切成多个子块"
        merged = "".join(chunk.content for chunk in result.children)
        for sentence in split_sentences(source):
            assert sentence in merged, f"句子被截断或丢失: {sentence}"

    def test_child_parent_linkage(self) -> None:
        """每个子块的 parent_id 必须指向真实存在的父块。"""
        doc = StructuredDoc(
            doc_id="doc-4",
            doc_type=DocType.TEXT,
            title="t",
            blocks=[Block(kind=BlockKind.PARAGRAPH, text=_long_paragraph(20))],
        )
        result = split(doc, SplitConfig(child_target_tokens=50, parent_max_tokens=120))

        parent_ids = {parent.parent_id for parent in result.parents}
        assert parent_ids, "应产出父块"
        for chunk in result.children:
            assert chunk.parent_id in parent_ids
        # 每个父块至少关联一个子块
        linked = {chunk.parent_id for chunk in result.children}
        assert linked == parent_ids

    def test_adjacent_chunks_overlap(self) -> None:
        """相邻子块需保留尾部重叠，防止跨块语义断裂。"""
        # 短句（约 4 token）+ 默认 512/0.15 配置预算，可回捞 1 句作为重叠
        source = "".join(f"第{index}句。" for index in range(40))
        doc = StructuredDoc(
            doc_id="doc-5",
            doc_type=DocType.TEXT,
            title="t",
            blocks=[Block(kind=BlockKind.PARAGRAPH, text=source)],
        )
        result = split(doc, SplitConfig(child_target_tokens=50, overlap_ratio=0.15))

        assert len(result.children) >= 2
        first_lines = result.children[0].content.splitlines()
        second_lines = result.children[1].content.splitlines()
        assert first_lines[-1] in second_lines, "下一子块应包含上一子块的末尾内容作为重叠"

    def test_metadata_injected(self) -> None:
        """页码、工作表等溯源元数据应注入子块。"""
        doc = StructuredDoc(
            doc_id="doc-6",
            doc_type=DocType.PDF,
            title="t",
            tenant_id="t1",
            kb_id="kb1",
            blocks=[
                Block(kind=BlockKind.HEADING, text="第一章", level=1, page=1),
                Block(kind=BlockKind.TABLE_ROW, text="型号：X1；价格：399", page=3, row_range="2-2"),
            ],
        )
        result = split(doc)
        chunk = result.children[0]
        assert chunk.doc_type is DocType.PDF
        assert chunk.tenant_id == "t1"
        assert chunk.kb_id == "kb1"
        assert chunk.page == 3
        assert chunk.meta["row_range"] == "2-2"
        assert chunk.seq == 1

    def test_empty_document(self) -> None:
        doc = StructuredDoc(doc_id="doc-7", doc_type=DocType.TEXT, title="t", blocks=[])
        result = split(doc)
        assert result.children == []
        assert result.parents == []
