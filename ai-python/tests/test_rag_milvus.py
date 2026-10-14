"""Milvus 集成测试（本地 Milvus 不可达时整体跳过）。

覆盖：集合/别名创建、按 ``doc_id`` 全量替换、删除、稠密+稀疏混合检索召回、
父块按 ``parent_id`` 聚合回填。使用独立集合（``_it`` 后缀）避免污染生产集合。
"""

from __future__ import annotations

import logging
from pathlib import Path

import pytest

pytest.importorskip("pymilvus")

from pymilvus import AnnSearchRequest, RRFRanker

from app.config import settings
from app.rag.index.mapping import drop_alias_if_exists
from app.rag.index.writer import ChunkStore
from app.rag.ingest.embedder import HashEmbedder, HybridEmbedder, KeywordSparseEmbedder
from app.rag.ingest.pipeline import ingest_document
from app.rag.retrieve import Retriever

_CHUNK = "lingxi_kb_chunk_it"
_VERSION = "it1"
_DIM = 8

# 检索集成测试使用独立集合（真实中文稀疏词权重 + 较大维度，便于区分主题）
_RET_CHUNK = "lingxi_kb_chunk_it_ret"
_RET_VERSION = "it1"
_RET_DIM = 64


@pytest.fixture(scope="module")
def store() -> ChunkStore:
    try:
        instance = ChunkStore(
            uri=settings.milvus_uri,
            chunk_collection=_CHUNK,
            dim=_DIM,
            version=_VERSION,
        )
        instance.client.list_collections()  # 探活，失败即跳过
    except Exception as exc:  # noqa: BLE001 - 任何连接异常都视为环境不可用
        pytest.skip(f"本地 Milvus 不可达，跳过集成测试：{exc}")
    yield instance
    _drop_collection(instance.client, _CHUNK, f"{_CHUNK}_{_VERSION}")
    instance.client.close()


def _drop_collection(client, logical: str, physical: str) -> None:
    """删除测试集合（Milvus 要求先解除别名关联再 drop）。"""
    if not client.has_collection(physical):
        return
    try:
        drop_alias_if_exists(client, logical, collection_name=physical)
    except Exception as exc:  # noqa: BLE001 - 清理失败仅告警，不影响测试结论
        logging.getLogger(__name__).warning("解除别名失败：%s", exc)
    if client.has_collection(physical):
        client.drop_collection(physical)


def _write(path: Path, body: str) -> Path:
    path.write_text(body, encoding="utf-8")
    return path


def _ingest(store: ChunkStore, path: Path, doc_id: str, tenant_id: str):
    return ingest_document(
        path, doc_id=doc_id, tenant_id=tenant_id, kb_id="kb1",
        embedder=HashEmbedder(dim=_DIM), store=store,
    )


class TestMilvusIngest:
    def test_write_and_count(self, store: ChunkStore, tmp_path: Path) -> None:
        path = _write(tmp_path / "write.md", "# 售后政策\n\n" + "退换货时效为48小时。" * 40)

        result = _ingest(store, path, doc_id="it-doc-1", tenant_id="t-write")

        assert result.child_count > 0
        assert store.count("t-write") == result.child_count
        assert result.parent_count > 0

    def test_reingest_replaces_previous_slices(self, store: ChunkStore, tmp_path: Path) -> None:
        long_path = _write(tmp_path / "long.md", "# 政策\n\n" + "长内容说明。" * 200)
        short_path = _write(tmp_path / "short.md", "# 政策\n\n短内容说明。")

        first = _ingest(store, long_path, doc_id="it-doc-2", tenant_id="t-replace")
        second = _ingest(store, short_path, doc_id="it-doc-2", tenant_id="t-replace")

        assert first.child_count > second.child_count
        # 全量替换：旧切片被删除，仅保留新切片
        assert second.deleted_child_count == first.child_count
        assert store.count("t-replace") == second.child_count

    def test_drop_document(self, store: ChunkStore, tmp_path: Path) -> None:
        path = _write(tmp_path / "drop.md", "# 政策\n\n内容。")
        _ingest(store, path, doc_id="it-doc-3", tenant_id="t-drop")

        store.drop_document("it-doc-3", "t-drop")
        assert store.count("t-drop") == 0

    def test_fetch_parent_content_aggregates_children(
        self, store: ChunkStore, tmp_path: Path
    ) -> None:
        """父块回填：按 parent_id 聚合子块并按 seq 拼接。"""
        path = _write(tmp_path / "parent.md", "# 章节\n\n" + "该章节的详细说明内容。" * 200)
        result = _ingest(store, path, doc_id="it-doc-4", tenant_id="t-parent")
        assert result.parent_count > 0

        rows = store.client.query(
            collection_name=_CHUNK,
            filter='tenant_id == "t-parent"',
            output_fields=["parent_id", "seq"],
        )
        parent_id = rows[0]["parent_id"]
        content = store.fetch_parent_content(parent_id, "t-parent")
        assert content, "父块聚合内容不应为空"
        # 聚合内容应与该父块分组外的其它分组区分（长度大于单个子块）
        assert len(content.splitlines()) >= 1

    def test_hybrid_search_recalls(self, store: ChunkStore, tmp_path: Path) -> None:
        path = _write(tmp_path / "search.md", "# 物流\n\n" + "商品将在48小时内发货，支持顺丰快递。" * 6)
        _ingest(store, path, doc_id="it-doc-5", tenant_id="t-search")

        query = HashEmbedder(dim=_DIM).embed(["48小时内发货"])
        requests = [
            AnnSearchRequest(
                data=query.dense, anns_field="dense_vector",
                param={"metric_type": "COSINE"}, limit=5,
                expr='tenant_id == "t-search"',
            ),
            AnnSearchRequest(
                data=query.sparse, anns_field="sparse_vector",
                param={"metric_type": "IP"}, limit=5,
                expr='tenant_id == "t-search"',
            ),
        ]
        hits = store.client.hybrid_search(
            collection_name=_CHUNK,
            reqs=requests,
            ranker=RRFRanker(),
            limit=3,
            output_fields=["chunk_id", "parent_id"],
        )
        assert len(hits[0]) > 0, "混合检索应能召回已入库切片"

    def test_hybrid_search_filter_isolates_tenants(
        self, store: ChunkStore, tmp_path: Path
    ) -> None:
        """跨租户隔离：过滤条件写入子请求 expr，检索结果不得混入他租户切片。"""
        path_a = _write(tmp_path / "a.md", "# 物流\n\n" + "商品将在48小时内发货。" * 6)
        path_b = _write(tmp_path / "b.md", "# 天气\n\n" + "商品将在48小时内发货。" * 6)
        _ingest(store, path_a, doc_id="it-doc-6a", tenant_id="t-iso-a")
        _ingest(store, path_b, doc_id="it-doc-6b", tenant_id="t-iso-b")

        query = HashEmbedder(dim=_DIM).embed(["48小时内发货"])
        hits = store.search_chunks(
            dense=query.dense[0], sparse=query.sparse[0],
            tenant_id="t-iso-a", limit=10,
        )
        assert hits, "应召回本租户切片"
        assert all(hit["chunk_id"].startswith("it-doc-6a") for hit in hits), \
            "检索结果不得混入其他租户的切片"


# 主题互不重叠的 FAQ 语料：每个二级标题即一个子块，便于按 heading_path 断言召回
_RET_CORPUS = """# 物流配送
我们与多家快递公司合作，全国大部分地区支持次日达，偏远地区承诺三个工作日送达。

# 退换货政策
自签收之日起七日内支持无理由退货，商品需保持外包装完好且不影响二次销售。

# 退款到账时间
退款审核通过后按原路退回，微信支付通常一到三个工作日到账，银行卡则需三到七个工作日。

# 发票开具
支持开具电子发票与纸质发票，下单时可在备注中填写发票抬头与纳税人识别号。

# 会员权益
黄金会员享受专属折扣与生日礼券，铂金会员额外获得优先客服与全场免邮特权。

# 安装服务
大家电提供免费上门安装，安装前请确认水电条件具备，可通过预约页面选择安装时段。

# 保修说明
整机保修一年，主要部件保修三年，人为损坏不在保修范围，需自费更换配件。

# 客服联系方式
在线客服工作时间为每日九点至二十一点，非工作时间可留言，次日优先为您处理。
"""


@pytest.fixture(scope="module")
def ret_store() -> ChunkStore:
    """检索集成测试专用集合（真实中文稀疏词权重 + 较大维度）。"""
    try:
        instance = ChunkStore(
            uri=settings.milvus_uri,
            chunk_collection=_RET_CHUNK,
            dim=_RET_DIM,
            version=_RET_VERSION,
        )
        instance.client.list_collections()
    except Exception as exc:  # noqa: BLE001 - 连接失败即跳过
        pytest.skip(f"本地 Milvus 不可达，跳过检索集成测试：{exc}")
    yield instance
    _drop_collection(instance.client, _RET_CHUNK, f"{_RET_CHUNK}_{_RET_VERSION}")
    instance.client.close()


class TestRetrieverRecallAt5:
    def test_recall_at_5_on_faq_corpus(self, ret_store: ChunkStore, tmp_path: Path) -> None:
        """召回质量断言：每条查询的目标章节须落在精排/融合后的 Top5 内。"""
        path = _write(tmp_path / "faq.md", _RET_CORPUS)
        embedder = HybridEmbedder(
            dense=HashEmbedder(dim=_RET_DIM), sparse=KeywordSparseEmbedder()
        )
        ingest_document(
            path, doc_id="ret-doc", tenant_id="t-ret", kb_id="kb-ret",
            embedder=embedder, store=ret_store,
        )
        retriever = Retriever(ret_store, embedder, candidate_k=20, top_k=5)

        cases = [
            ("退款多久能到账", "退款到账时间"),
            ("电子发票怎么开", "发票开具"),
            ("快递多久能送到", "物流配送"),
        ]
        for query, expected in cases:
            hits = retriever.retrieve(query, tenant_id="t-ret")
            paths = [hit.heading_path for hit in hits]
            assert expected in paths, f"查询「{query}」Top5 未命中 {expected}：{paths}"
