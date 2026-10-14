"""结构感知递归切分器：章节父块 + 子块（含句界重叠与元数据注入）。

切分五步（对应《架构设计》7.4.4）：
1. **解析成结构树**：由解析器产出带标题层级的 ``Block`` 序列；
2. **递归切分**：本模块按标题层级划分章节（结构边界优先），章节内超长内容
   再按句界二次切分（见 :mod:`app.rag.ingest.tokenizer`）；
3. **子块生成**：目标 256~512 token，句界对齐，相邻子块保留重叠防跨块语义断裂；
4. **父块归并**：同一章节内子块按 1k~2k token 归并为父块，子块写入 ``parent_id``；
5. **元数据注入**：子块携带 ``heading_path`` 与页码/工作表等溯源信息。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.rag.ingest.tokenizer import count_tokens, split_sentences
from app.rag.models import (
    Block,
    BlockKind,
    Chunk,
    ParentChunk,
    SplitResult,
    StructuredDoc,
)


@dataclass(slots=True)
class SplitConfig:
    """切分参数（默认值取《架构设计》7.4.4 推荐区间）。"""

    child_target_tokens: int = 512  # 子块目标上限（256~512 区间取上限）
    overlap_ratio: float = 0.15  # 相邻子块重叠比例（10%~15%）
    parent_max_tokens: int = 2000  # 父块上限（1k~2k 区间取上限）


@dataclass(slots=True)
class _Unit:
    """切分内部单元：句界对齐后的最小装箱单位，携带来源溯源信息。"""

    text: str
    page: int | None = None
    slide_no: int | None = None
    sheet: str | None = None
    row_range: str | None = None


@dataclass(slots=True)
class _Section:
    """章节：以标题为边界的连续结构块集合。"""

    heading_path: str
    blocks: list[Block]


def split(doc: StructuredDoc, config: SplitConfig | None = None) -> SplitResult:
    """把结构化文档切分为子块与父块。

    Args:
        doc: 解析器输出的结构化文档。
        config: 切分参数，缺省使用 :class:`SplitConfig` 默认值。

    Returns:
        :class:`SplitResult`，其中 ``children`` 为子块（含 ``parent_id`` 指向
        父块）、``parents`` 为父块。
    """
    cfg = config or SplitConfig()
    children: list[Chunk] = []
    parents: list[ParentChunk] = []
    child_seq = 0
    parent_seq = 0

    for section in _to_sections(doc):
        if not section.blocks:
            continue
        # 子块：章节内按句界单元装箱，带重叠
        section_children: list[Chunk] = []
        for group in _pack(_expand_units(section.blocks, cfg), cfg):
            child_seq += 1
            section_children.append(_build_chunk(doc, section, group, child_seq))

        # 父块：子块按 token 预算归并，并回填 parent_id
        for parent_group in _group_for_parents(section_children, cfg):
            parent_seq += 1
            parent_id = f"{doc.doc_id}-p{parent_seq:04d}"
            for child in parent_group:
                child.parent_id = parent_id
            parents.append(
                ParentChunk(
                    parent_id=parent_id,
                    doc_id=doc.doc_id,
                    content="\n".join(child.content for child in parent_group),
                    heading_path=section.heading_path,
                    seq=parent_seq,
                    tenant_id=doc.tenant_id,
                    kb_id=doc.kb_id,
                )
            )
        children.extend(section_children)

    return SplitResult(children=children, parents=parents)


def _to_sections(doc: StructuredDoc) -> list[_Section]:
    """按标题层级把结构块划分为章节，并维护多级标题路径。

    标题本身不进入子块内容（其信息通过 ``heading_path`` 承载）；
    连续标题产生的空章节会被跳过。
    """
    sections: list[_Section] = []
    heading_stack: list[tuple[int, str]] = []
    current: list[Block] = []

    def flush() -> None:
        # 空章节（标题下无正文）无需产出，但仍需保留标题栈供后续章节继承
        if current:
            sections.append(_Section(heading_path=_heading_path(doc, heading_stack), blocks=list(current)))
        current.clear()

    for block in doc.blocks:
        if block.kind is BlockKind.HEADING:
            flush()
            # 同级或更深的旧标题出栈，形成正确的多级路径
            while heading_stack and heading_stack[-1][0] >= block.level:
                heading_stack.pop()
            heading_stack.append((block.level, block.text.strip()))
            continue
        current.append(block)
    flush()
    return sections


def _heading_path(doc: StructuredDoc, heading_stack: list[tuple[int, str]]) -> str:
    """拼接标题路径（如 ``售后政策 > 退换货 > 时效``）；无标题时回退文档标题。"""
    if not heading_stack:
        return doc.title
    return " > ".join(text for _, text in heading_stack if text)


def _expand_units(blocks: list[Block], cfg: SplitConfig) -> list[_Unit]:
    """把结构块展开为句界单元。

    未超预算的块整体作为一个单元（保留段落完整性）；超长块按句界拆分为
    多个单元，**绝不在句子中间截断**。
    """
    units: list[_Unit] = []
    for block in blocks:
        if block.kind is BlockKind.HEADING:
            continue
        text = block.text.strip()
        if not text:
            continue
        parts = split_sentences(text) if count_tokens(text) > cfg.child_target_tokens else [text]
        for part in parts or [text]:
            units.append(
                _Unit(
                    text=part,
                    page=block.page,
                    slide_no=block.slide_no,
                    sheet=block.sheet,
                    row_range=block.row_range,
                )
            )
    return units


def _pack(units: list[_Unit], cfg: SplitConfig) -> list[list[_Unit]]:
    """按 token 预算把单元贪心装箱为子块，相邻子块保留尾部重叠。"""
    groups: list[list[_Unit]] = []
    current: list[_Unit] = []
    current_tokens = 0

    for unit in units:
        unit_tokens = count_tokens(unit.text)
        # 超预算则封箱，并把当前组尾部的单元回捞为下一组的重叠前缀
        if current and current_tokens + unit_tokens > cfg.child_target_tokens:
            groups.append(current)
            carry = _tail_overlap(current, cfg)
            current = list(carry)
            current_tokens = sum(count_tokens(item.text) for item in carry)
        current.append(unit)
        current_tokens += unit_tokens

    if current:
        groups.append(current)
    return groups


def _tail_overlap(group: list[_Unit], cfg: SplitConfig) -> list[_Unit]:
    """从组尾部回捞不超过重叠预算的单元（整组即重叠时返回空，避免重复装箱）。"""
    budget = int(cfg.child_target_tokens * cfg.overlap_ratio)
    if budget <= 0:
        return []
    tail: list[_Unit] = []
    accumulated = 0
    for unit in reversed(group):
        unit_tokens = count_tokens(unit.text)
        if accumulated + unit_tokens > budget:
            break
        tail.insert(0, unit)
        accumulated += unit_tokens
    return [] if len(tail) >= len(group) else tail


def _build_chunk(doc: StructuredDoc, section: _Section, group: list[_Unit], seq: int) -> Chunk:
    """由单元组构造子块，注入 heading_path 与溯源元数据（parent_id 待回填）。"""
    head = group[0]
    meta: dict[str, str] = {}
    if head.sheet:
        meta["sheet"] = head.sheet
    if head.row_range:
        meta["row_range"] = head.row_range
    if head.slide_no is not None:
        meta["slide_no"] = str(head.slide_no)
    return Chunk(
        chunk_id=f"{doc.doc_id}-c{seq:05d}",
        doc_id=doc.doc_id,
        kb_id=doc.kb_id,
        tenant_id=doc.tenant_id,
        parent_id="",  # 由父块归并阶段回填
        content="\n".join(unit.text for unit in group),
        seq=seq,
        heading_path=section.heading_path,
        doc_type=doc.doc_type,
        page=head.page,
        meta=meta,
    )


def _group_for_parents(children: list[Chunk], cfg: SplitConfig) -> list[list[Chunk]]:
    """把章节内子块按 token 预算归并为父块分组。"""
    groups: list[list[Chunk]] = []
    current: list[Chunk] = []
    accumulated = 0
    for child in children:
        child_tokens = count_tokens(child.content)
        if current and accumulated + child_tokens > cfg.parent_max_tokens:
            groups.append(current)
            current = []
            accumulated = 0
        current.append(child)
        accumulated += child_tokens
    if current:
        groups.append(current)
    return groups
