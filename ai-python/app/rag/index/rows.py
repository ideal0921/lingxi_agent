"""切分产物 → Milvus 行 的纯函数转换（不依赖 Milvus SDK，便于离线单测）。"""

from __future__ import annotations

from collections.abc import Mapping, Sequence

from app.rag.models import Chunk

# 无页码/无幻灯片页时的占位值（Milvus 标量字段不接受 None）
PAGE_ABSENT = -1


def chunk_rows(
    chunks: Sequence[Chunk],
    dense: Sequence[Sequence[float]],
    sparse: Sequence[Mapping[int, float]],
) -> list[dict]:
    """把子块与向量组装为 Milvus 行。

    Raises:
        ValueError: 向量数量与子块数量不一致时抛出，防止向量与分析产物错位。
    """
    if not (len(chunks) == len(dense) == len(sparse)):
        raise ValueError("子块数量与稠密/稀疏向量数量不一致，拒绝写入")
    rows: list[dict] = []
    for index, chunk in enumerate(chunks):
        rows.append(
            {
                "chunk_id": chunk.chunk_id,
                "doc_id": chunk.doc_id,
                "kb_id": chunk.kb_id,
                "tenant_id": chunk.tenant_id,
                "parent_id": chunk.parent_id,
                "content": chunk.content,
                "seq": chunk.seq,
                "heading_path": chunk.heading_path,
                "doc_type": str(chunk.doc_type),
                "page": chunk.page if chunk.page is not None else PAGE_ABSENT,
                "meta": dict(chunk.meta),
                "dense_vector": list(dense[index]),
                "sparse_vector": dict(sparse[index]),
            }
        )
    return rows
