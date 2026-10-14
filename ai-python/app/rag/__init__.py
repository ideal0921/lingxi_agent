"""知识库 RAG 模块：文档解析 → 结构感知切分 → 向量化 → 索引 → 混合检索精排。

子模块：
- :mod:`app.rag.models`：统一中间表示（Block/Chunk/ParentChunk 等）；
- :mod:`app.rag.ingest`：解析、切分、向量化与入库管道；
- :mod:`app.rag.index`：Milvus 集合定义、行构造与写入/检索存储；
- :mod:`app.rag.retrieve`：混合检索（RRFRanker）→ 精排 TopK → 父块回填；
- :mod:`app.rag.rerank`：云端精排（bge-reranker-v2-m3）与降级实现。
"""

from app.rag.models import (
    Block,
    BlockKind,
    Chunk,
    DocType,
    ParentChunk,
    SplitResult,
    StructuredDoc,
)

__all__ = [
    "Block",
    "BlockKind",
    "Chunk",
    "DocType",
    "ParentChunk",
    "SplitResult",
    "StructuredDoc",
]
