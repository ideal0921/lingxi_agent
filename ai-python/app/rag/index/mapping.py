"""Milvus 集合定义：子块 schema、向量索引与别名管理。

**设计要点（M3-3.5 实现修正）**：Milvus 2.6 不允许创建**无向量字段的集合**
（纯标量集合被拒），故不再为父块单独建 Collection。父块改为「同集合内按
``parent_id`` 聚合子块回填」——检索命中子块后，按其 ``parent_id`` 回查同组
子块并按 ``seq`` 拼接，即得章节级上下文。该方案单一存储、无冗余、无跨库，
且父块边界由切分器的 ``parent_id`` 分组天然保证。

集合结构（对齐《架构设计》7.4.6，父块独立 Collection 改为聚合回填）：
- ``chunk_id`` 主键；``tenant_id`` 作 Partition Key（16 分区，天然多租户隔离）；
- 稠密 ``FLOAT_VECTOR`` + 稀疏 ``SPARSE_FLOAT_VECTOR``（混合检索）；
- ``doc_id`` / ``parent_id`` 建倒排索引，加速按文档替换与父块回填；
- 逻辑名（别名）``lingxi_kb_chunk`` 指向物理集合 ``lingxi_kb_chunk_v{n}``，
  便于结构升级时零停机切换。
"""

from __future__ import annotations

from pymilvus import DataType, MilvusClient

# VARCHAR 长度上限（Milvus 上限 65535，此处留足余量）
_CONTENT_MAX_LENGTH = 8192
_PATH_MAX_LENGTH = 1024
_SMALL_MAX_LENGTH = 128

# 稠密索引参数：HNSW + COSINE（召回率与延迟折中）
_HNSW_PARAMS = {"M": 16, "efConstruction": 200}


def physical_name(logical_name: str, version: str) -> str:
    """由逻辑名与版本推导物理集合名（``lingxi_kb_chunk`` → ``lingxi_kb_chunk_v1``）。"""
    return f"{logical_name}_{version}"


def create_chunk_collection(client: MilvusClient, physical: str, dim: int) -> None:
    """创建子块集合（稠密 + 稀疏索引 + 标量倒排索引）。"""
    schema = client.create_schema(auto_id=False, enable_dynamic_field=True)
    schema.add_field("chunk_id", DataType.VARCHAR, is_primary=True, max_length=_SMALL_MAX_LENGTH)
    schema.add_field("doc_id", DataType.VARCHAR, max_length=_SMALL_MAX_LENGTH)
    schema.add_field("kb_id", DataType.VARCHAR, max_length=_SMALL_MAX_LENGTH)
    # Partition Key：按租户物理隔离，检索与删除必须携带 tenant_id 过滤
    schema.add_field("tenant_id", DataType.VARCHAR, max_length=64, is_partition_key=True)
    schema.add_field("parent_id", DataType.VARCHAR, max_length=_SMALL_MAX_LENGTH)
    schema.add_field("content", DataType.VARCHAR, max_length=_CONTENT_MAX_LENGTH)
    schema.add_field("seq", DataType.INT64)
    schema.add_field("heading_path", DataType.VARCHAR, max_length=_PATH_MAX_LENGTH)
    schema.add_field("doc_type", DataType.VARCHAR, max_length=32)
    schema.add_field("page", DataType.INT64)  # 无页码记为 -1
    schema.add_field("meta", DataType.JSON)
    schema.add_field("dense_vector", DataType.FLOAT_VECTOR, dim=dim)
    schema.add_field("sparse_vector", DataType.SPARSE_FLOAT_VECTOR)

    index_params = client.prepare_index_params()
    index_params.add_index(
        "dense_vector", index_type="HNSW", metric_type="COSINE", params=_HNSW_PARAMS
    )
    index_params.add_index("sparse_vector", index_type="SPARSE_INVERTED_INDEX", metric_type="IP")
    # 标量倒排索引：加速按文档全量替换与父块聚合回填
    index_params.add_index("doc_id", index_type="INVERTED")
    index_params.add_index("parent_id", index_type="INVERTED")

    client.create_collection(collection_name=physical, schema=schema, index_params=index_params)


def ensure_collection(
    client: MilvusClient, logical_name: str, physical: str, *, dim: int
) -> str:
    """确保逻辑名（别名）可解析到物理集合；不存在时创建物理集合并建立别名。

    Args:
        client: Milvus 客户端。
        logical_name: 逻辑集合名（别名），如 ``lingxi_kb_chunk``。
        physical: 物理集合名，如 ``lingxi_kb_chunk_v1``。
        dim: 稠密向量维度。

    Returns:
        实际使用的逻辑名（写入与检索统一用它，屏蔽版本切换）。
    """
    if client.has_collection(logical_name):
        return logical_name
    if not client.has_collection(physical):
        create_chunk_collection(client, physical, dim)
    client.create_alias(collection_name=physical, alias=logical_name)
    return logical_name


def drop_alias_if_exists(client: MilvusClient, alias: str, *, collection_name: str) -> None:
    """解除别名关联。

    注意：Milvus 要求**先解除别名关联才能 drop 集合**，该约束在集合重建
    （结构升级）时必须遵守。
    """
    aliases = client.list_aliases(collection_name=collection_name).get("aliases", [])
    if alias in aliases:
        client.drop_alias(alias=alias)
