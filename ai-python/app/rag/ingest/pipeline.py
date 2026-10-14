"""知识入库管道：解析 → 切分 → 向量化 → 索引 → 状态回写。

触发方式（《架构设计》7.4.2）：
- **异步（生产默认）**：Kafka 主题 ``kb.doc.ingest`` 驱动（Java 知识库域上传后投递），
  完成后经状态回写钩子发送 ``kb.doc.indexed``，由 Java 更新文档版本状态；
- **同步（本地联调/单测）**：直接调用 :func:`ingest_document`，两者共用同一编排逻辑。

依赖注入：``embedder`` 与 ``store`` 均由调用方传入，管道本身不绑定 Milvus SDK，
便于离线单测与替换实现。

父块存储说明：Milvus 2.6 不支持无向量字段的集合，故父块不单独落库；检索命中
子块后按 ``parent_id`` 聚合同组子块拼接即得章节级上下文（见 ``ChunkStore``）。
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING

from app.config import settings
from app.privacy import PiiMasker
from app.rag.ingest.embedder import Embedder
from app.rag.ingest.parser import parse_document
from app.rag.ingest.splitter import SplitConfig, split
from app.rag.models import DocType

if TYPE_CHECKING:  # 仅类型标注，避免运行时强依赖 pymilvus
    from app.rag.index.writer import ChunkStore


def _new_masker() -> PiiMasker:
    """按应用配置创建脱敏器（入库向量化前遮蔽 PII）。"""
    return PiiMasker(enabled=settings.pii_enabled, mask_address=settings.pii_mask_address)


@dataclass(slots=True)
class IngestEvent:
    """入库事件（对应 Kafka 主题 ``kb.doc.ingest`` 的消息体）。"""

    doc_id: str
    tenant_id: str
    kb_id: str
    file_path: str


@dataclass(slots=True)
class IngestResult:
    """入库结果：供状态回写（``kb.doc.indexed``）与审计。

    ``parent_count`` 为切分器产出的父块分组数；父块内容不单独落库，检索命中
    后由 :meth:`ChunkStore.fetch_parent_content` 按 ``parent_id`` 聚合回填。
    """

    doc_id: str
    tenant_id: str
    kb_id: str
    doc_type: DocType
    block_count: int
    child_count: int
    parent_count: int
    deleted_child_count: int


def ingest_document(
    path: str | Path,
    *,
    doc_id: str,
    tenant_id: str,
    kb_id: str,
    embedder: Embedder,
    store: ChunkStore,
    config: SplitConfig | None = None,
    pii_masker: PiiMasker | None = None,
) -> IngestResult:
    """把单个文档解析、切分、向量化并写入 Milvus（按 ``doc_id`` 全量替换）。

    PII 脱敏（M3-3.9）：**向量化前**先遮蔽敏感信息，避免原文随云端 embedding 调用
    外传；落库的 ``content`` 仍保留原文，供引用展示与答案还原。

    Args:
        path: 文档路径（扩展名决定解析器）。
        doc_id: 文档唯一标识。
        tenant_id: 租户标识（Partition Key，隔离多租户知识）。
        kb_id: 知识库标识。
        embedder: 向量化器。
        store: Milvus 存储（子块 + 父块）。
        config: 切分参数，缺省使用默认值。
        pii_masker: 脱敏器；缺省按应用配置创建。

    Returns:
        :class:`IngestResult`。
    """
    structured = parse_document(path, doc_id, tenant_id=tenant_id, kb_id=kb_id)
    split_result = split(structured, config)
    # 空文档同样执行替换，以清空该文档的历史切片
    masker = pii_masker or _new_masker()
    embed_texts = [masker.mask(chunk.content) for chunk in split_result.children]
    embedded = embedder.embed(embed_texts)

    replaced = store.replace_document(
        doc_id=doc_id,
        tenant_id=tenant_id,
        chunks=split_result.children,
        dense=embedded.dense,
        sparse=embedded.sparse,
    )
    return IngestResult(
        doc_id=doc_id,
        tenant_id=tenant_id,
        kb_id=kb_id,
        doc_type=structured.doc_type,
        block_count=len(structured.blocks),
        child_count=replaced.child_count,
        parent_count=len(split_result.parents),
        deleted_child_count=replaced.deleted_child_count,
    )


def handle_ingest_event(
    event: IngestEvent,
    *,
    embedder: Embedder,
    store: ChunkStore,
    on_indexed: Callable[[IngestResult], None] | None = None,
    config: SplitConfig | None = None,
    pii_masker: PiiMasker | None = None,
) -> IngestResult:
    """处理 Kafka 入库事件并回写索引状态。

    Args:
        event: 入库事件。
        embedder: 向量化器。
        store: Milvus 存储。
        on_indexed: 状态回写钩子；生产实现为向 ``kb.doc.indexed`` 投递消息，
            由 Java 知识库域更新文档版本状态。缺省不回调（本地联调场景）。
        config: 切分参数。
        pii_masker: 脱敏器（向量化前遮蔽 PII）；缺省按应用配置创建。

    Returns:
        :class:`IngestResult`。
    """
    result = ingest_document(
        event.file_path,
        doc_id=event.doc_id,
        tenant_id=event.tenant_id,
        kb_id=event.kb_id,
        embedder=embedder,
        store=store,
        config=config,
        pii_masker=pii_masker,
    )
    if on_indexed is not None:
        on_indexed(result)
    return result
