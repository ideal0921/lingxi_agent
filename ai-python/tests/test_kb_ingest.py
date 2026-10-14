"""知识库入库消费者单测（M3-3.2，离线运行，不连 Kafka / 不触网 / 不连 Milvus）。

覆盖：事件解析与非法事件忽略、入库成功回写 INDEXED、入库失败回写 FAILED、
临时文件清理、下载失败不产生回写。
"""

from __future__ import annotations

import asyncio
import time
from pathlib import Path

from app.config import Settings
from app.consumers.kb_ingest import (
    KbIngestEvent,
    build_indexed_payload,
    handle_kb_delete,
    handle_kb_ingest,
)
from app.rag.ingest.embedder import HashEmbedder


class _FakeProducer:
    """记录发布内容的生产者替身。"""

    def __init__(self) -> None:
        self.published: list[dict] = []

    async def publish(self, topic: str, payload: dict, key: str | None = None) -> None:
        self.published.append({"topic": topic, "payload": payload, "key": key})

    def by_topic(self, topic: str) -> list[dict]:
        return [item["payload"] for item in self.published if item["topic"] == topic]


class _FakeReplace:
    """replace_document 返回值替身（仅暴露管道读取的字段）。"""

    def __init__(self, child_count: int) -> None:
        self.child_count = child_count
        self.deleted_child_count = 0


class _FakeStore:
    """假存储：记录写入并按子块数返回结果；可配置抛错或阻塞以验证降级与并发。"""

    def __init__(self, *, boom: bool = False, delay: float = 0.0) -> None:
        self.boom = boom
        self.delay = delay
        self.replaced: dict | None = None

    def replace_document(self, *, doc_id, tenant_id, chunks, dense, sparse) -> _FakeReplace:
        if self.delay:
            time.sleep(self.delay)  # 模拟同步阻塞（Milvus 写入 / 嵌入网络往返）
        if self.boom:
            raise RuntimeError("Milvus 不可达")
        self.replaced = {
            "doc_id": doc_id,
            "tenant_id": tenant_id,
            "chunks": chunks,
            "dense": dense,
            "sparse": sparse,
        }
        return _FakeReplace(child_count=len(chunks))


class _FakeDeleteStore:
    """下线清理替身：记录调用参数并返回删除计数。"""

    def __init__(self, deleted_child_count: int = 3) -> None:
        self.calls: list[tuple[str, str]] = []
        self._deleted = deleted_child_count

    def drop_document(self, doc_id: str, tenant_id: str) -> _FakeDropResult:
        self.calls.append((doc_id, tenant_id))
        return _FakeDropResult(deleted_child_count=self._deleted)


class _FakeDropResult:
    def __init__(self, deleted_child_count: int) -> None:
        self.deleted_child_count = deleted_child_count


def _config() -> Settings:
    return Settings(
        kafka_topic_kb_ingest="kb-ingest-topic",
        kafka_topic_kb_indexed="kb-indexed-topic",
    )


def _download_to(path: Path):
    """构造下载替身：返回预设的本地文件路径。"""

    async def _downloader(_: KbIngestEvent) -> Path:
        return path

    return _downloader


class TestEventParsing:
    def test_from_payload_maps_fields(self) -> None:
        event = KbIngestEvent.from_payload(
            {
                "doc_id": "d1",
                "tenant_id": "t1",
                "kb_id": "kb1",
                "version": 3,
                "file_name": "policy.pdf",
                "file_url": "http://minio/presigned",
            }
        )
        assert (event.doc_id, event.tenant_id, event.kb_id) == ("d1", "t1", "kb1")
        assert event.version == 3
        assert event.valid is True

    def test_missing_file_url_is_invalid(self) -> None:
        assert KbIngestEvent.from_payload({"doc_id": "d1"}).valid is False

    def test_indexed_payload_shape(self) -> None:
        payload = build_indexed_payload(
            KbIngestEvent(doc_id="d1", tenant_id="t1", kb_id="kb1", version=2),
            status="INDEXED",
            child_count=7,
            parent_count=3,
        )
        assert payload == {
            "doc_id": "d1",
            "tenant_id": "t1",
            "kb_id": "kb1",
            "version": 2,
            "status": "INDEXED",
            "child_count": 7,
            "parent_count": 3,
            "message": "",
        }


class TestKbIngestHandling:
    async def test_success_writes_indexed_status(self, tmp_path: Path) -> None:
        doc = tmp_path / "policy.md"
        doc.write_text("# 售后政策\n\n" + "这是正文内容。" * 200, encoding="utf-8")
        producer = _FakeProducer()
        store = _FakeStore()

        await handle_kb_ingest(
            {
                "doc_id": "d1",
                "tenant_id": "t1",
                "kb_id": "kb1",
                "version": 1,
                "file_name": "policy.md",
                "file_url": "http://minio/presigned",
            },
            config=_config(),
            producer=producer,
            store=store,
            embedder=HashEmbedder(dim=16),
            downloader=_download_to(doc),
        )

        indexed = producer.by_topic("kb-indexed-topic")
        assert len(indexed) == 1
        assert indexed[0]["status"] == "INDEXED"
        assert indexed[0]["doc_id"] == "d1"
        assert indexed[0]["child_count"] > 0
        assert producer.published[0]["key"] == "d1"  # 文档键分区
        assert store.replaced is not None  # 确实写入了 Milvus

    async def test_ingest_failure_writes_failed_status(self, tmp_path: Path) -> None:
        doc = tmp_path / "policy.md"
        doc.write_text("内容。", encoding="utf-8")
        producer = _FakeProducer()

        await handle_kb_ingest(
            {
                "doc_id": "d2",
                "tenant_id": "t1",
                "kb_id": "kb1",
                "file_name": "policy.md",
                "file_url": "http://minio/presigned",
            },
            config=_config(),
            producer=producer,
            store=_FakeStore(boom=True),
            embedder=HashEmbedder(dim=8),
            downloader=_download_to(doc),
        )

        indexed = producer.by_topic("kb-indexed-topic")
        assert len(indexed) == 1
        assert indexed[0]["status"] == "FAILED"
        assert "Milvus 不可达" in indexed[0]["message"]

    async def test_invalid_event_is_ignored(self) -> None:
        producer = _FakeProducer()

        await handle_kb_ingest(
            {"doc_id": "d3", "content": "无文件地址"},
            config=_config(),
            producer=producer,
            store=_FakeStore(),
            embedder=HashEmbedder(dim=8),
        )

        assert producer.published == []

    async def test_temp_file_is_cleaned_up(self, tmp_path: Path) -> None:
        doc = tmp_path / "policy.md"
        doc.write_text("内容。", encoding="utf-8")
        producer = _FakeProducer()

        await handle_kb_ingest(
            {
                "doc_id": "d4",
                "tenant_id": "t1",
                "kb_id": "kb1",
                "file_name": "policy.md",
                "file_url": "http://minio/presigned",
            },
            config=_config(),
            producer=producer,
            store=_FakeStore(),
            embedder=HashEmbedder(dim=8),
            downloader=_download_to(doc),
        )

        assert not doc.exists()  # 临时文件无论成败都应清理

    async def test_download_failure_writes_failed_status(self) -> None:
        producer = _FakeProducer()

        async def _boom(_: KbIngestEvent) -> Path:
            raise RuntimeError("下载失败")

        await handle_kb_ingest(
            {
                "doc_id": "d5",
                "tenant_id": "t1",
                "kb_id": "kb1",
                "file_name": "policy.md",
                "file_url": "http://minio/gone",
            },
            config=_config(),
            producer=producer,
            store=_FakeStore(),
            embedder=HashEmbedder(dim=8),
            downloader=_boom,
        )

        # 下载失败也要回写 FAILED，否则文档会永久停留在「处理中」
        indexed = producer.by_topic("kb-indexed-topic")
        assert len(indexed) == 1
        assert indexed[0]["status"] == "FAILED"
        assert "下载失败" in indexed[0]["message"]

    async def test_ingest_runs_off_event_loop(self, tmp_path: Path) -> None:
        """回归防护：入库是同步阻塞调用，必须放工作线程，否则并发入库相互阻塞。"""
        doc = tmp_path / "policy.md"
        doc.write_text("内容。", encoding="utf-8")

        ticks = 0

        async def _ticker() -> None:
            nonlocal ticks
            while True:
                await asyncio.sleep(0.02)
                ticks += 1

        ticker = asyncio.create_task(_ticker())
        try:
            await handle_kb_ingest(
                {
                    "doc_id": "d6",
                    "tenant_id": "t1",
                    "kb_id": "kb1",
                    "file_name": "policy.md",
                    "file_url": "http://minio/presigned",
                },
                config=_config(),
                producer=_FakeProducer(),
                store=_FakeStore(delay=0.3),  # 阻塞 300ms
                embedder=HashEmbedder(dim=8),
                downloader=_download_to(doc),
            )
        finally:
            ticker.cancel()
            try:
                await ticker
            except asyncio.CancelledError:
                pass

        # 阻塞 300ms 期间事件循环应持续心跳；若在主线程执行则心跳接近 0
        assert ticks >= 5, f"入库期间事件循环被阻塞（仅 {ticks} 次心跳）"


class TestKbDeleteHandling:
    async def test_delete_drops_document_chunks(self) -> None:
        store = _FakeDeleteStore(deleted_child_count=5)

        await handle_kb_delete(
            {"doc_id": "d1", "tenant_id": "t1", "kb_id": "kb1"}, store=store
        )

        # 按 doc_id + tenant_id 精确删除，避免误删其他租户数据
        assert store.calls == [("d1", "t1")]

    async def test_invalid_delete_event_is_ignored(self) -> None:
        store = _FakeDeleteStore()

        await handle_kb_delete({"doc_id": "d1"}, store=store)  # 缺少 tenant_id
        await handle_kb_delete({"tenant_id": "t1"}, store=store)  # 缺少 doc_id

        assert store.calls == []
