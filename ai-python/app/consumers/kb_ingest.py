"""知识库入库消费（M3-3.2）：消费 ``kb.doc.ingest`` → 拉取原件入库 → 回写 ``kb.doc.indexed``。

链路（《架构设计》7.4.2）::

    Java 知识库域上传落 MinIO ──▶ kb.doc.ingest（本消费者）
      → 预签名 URL 下载原件到临时文件（保留扩展名，供解析器分派）
      → 解析 / 结构感知切分 / 向量化 / 写入 Milvus（按 doc_id 全量替换）
      → kb.doc.indexed（Java 消费后回写文档版本状态）

**失败也回写**：入库异常同样投递 ``status=FAILED`` 事件（携带原因），避免文档
永久停留在「处理中」；临时文件无论成败都清理。

**不阻塞事件循环**：解析/切分/嵌入/Milvus 写入均为同步阻塞调用，统一经
``asyncio.to_thread`` 放入工作线程执行（与对话链路一致，支持多文档并发入库）。
"""

from __future__ import annotations

import asyncio
import logging
import os
import tempfile
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

from app.config import Settings
from app.messaging.kafka import KafkaProducer

logger = logging.getLogger(__name__)

# 下载回调：把入库事件对应的原始文档落到本地临时文件，返回其路径
Downloader = Callable[["KbIngestEvent"], Awaitable[Path]]


@dataclass(slots=True)
class KbIngestEvent:
    """``kb.doc.ingest`` 事件体（Java 知识库域上传后投递）。"""

    doc_id: str
    tenant_id: str
    kb_id: str
    version: int = 1
    file_name: str = ""
    file_url: str = ""  # MinIO 预签名下载 URL（避免 Python 侧引入对象存储 SDK）

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> KbIngestEvent:
        """从 Kafka 消息体构造事件（字段缺失由调用方校验兜底）。"""
        return cls(
            doc_id=str(payload.get("doc_id", "")),
            tenant_id=str(payload.get("tenant_id", "")),
            kb_id=str(payload.get("kb_id", "")),
            version=int(payload.get("version", 1) or 1),
            file_name=str(payload.get("file_name", "") or ""),
            file_url=str(payload.get("file_url", "") or ""),
        )

    @property
    def valid(self) -> bool:
        """文档入参是否足以完成入库（缺少文件地址则无法拉取原件）。"""
        return bool(self.doc_id and self.tenant_id and self.kb_id and self.file_url)


def build_indexed_payload(
    event: KbIngestEvent,
    *,
    status: str,
    child_count: int = 0,
    parent_count: int = 0,
    message: str = "",
) -> dict[str, Any]:
    """构造 ``kb.doc.indexed`` 事件体（供 Java 回写文档版本状态）。"""
    return {
        "doc_id": event.doc_id,
        "tenant_id": event.tenant_id,
        "kb_id": event.kb_id,
        "version": event.version,
        "status": status,
        "child_count": child_count,
        "parent_count": parent_count,
        "message": message,
    }


async def download_document(event: KbIngestEvent, *, timeout: float) -> Path:
    """从预签名 URL 流式下载原件到临时文件，并保留扩展名（解析器按扩展名分派）。

    整个下载（含写盘）放在工作线程执行，避免同步阻塞 I/O 卡住事件循环。

    Raises:
        RuntimeError: URL 不可用或响应非 2xx。
    """
    return await asyncio.to_thread(_download_sync, event, timeout)


def _download_sync(event: KbIngestEvent, timeout: float) -> Path:
    """同步实现（供 :func:`asyncio.to_thread` 调用）：流式落盘，失败清理半成品。"""
    suffix = Path(event.file_name).suffix or ".bin"
    fd, temp_path = tempfile.mkstemp(prefix="kb-ingest-", suffix=suffix)
    os.close(fd)
    path = Path(temp_path)
    try:
        with (
            httpx.Client(timeout=timeout) as client,
            client.stream("GET", event.file_url) as resp,
        ):
            resp.raise_for_status()
            with path.open("wb") as file:
                for block in resp.iter_bytes():
                    file.write(block)
    except Exception:
        # 下载失败立即清理半成品，避免残片被误当作完整文档解析
        path.unlink(missing_ok=True)
        raise
    return path


async def handle_kb_ingest(
    payload: dict[str, Any],
    *,
    config: Settings,
    producer: KafkaProducer,
    store: Any,
    embedder: Any,
    downloader: Downloader | None = None,
) -> None:
    """单条入库事件的完整处理：下载 → 入库 → 回写状态。

    Args:
        payload: ``kb.doc.ingest`` 消息体。
        config: 应用配置（主题名、下载超时）。
        producer: Kafka 生产者（回写 ``kb.doc.indexed``）。
        store: Milvus 子块存储（``ChunkStore``）。
        embedder: 向量化器。
        downloader: 下载实现；缺省用 :func:`download_document`（单测可注入替身）。
    """
    event = KbIngestEvent.from_payload(payload)
    if not event.valid:
        logger.warning("忽略非法的入库事件（缺少 doc_id/tenant_id/kb_id/file_url）: %s", payload)
        return

    fetch = downloader or (lambda item: download_document(item, timeout=config.kb_download_timeout))
    temp_path: Path | None = None
    try:
        temp_path = await fetch(event)
        # 解析/切分/嵌入/Milvus 写入均为同步阻塞调用，放工作线程避免阻塞事件循环
        result = await asyncio.to_thread(
            _ingest_sync,
            temp_path,
            event,
            store=store,
            embedder=embedder,
        )
        indexed = build_indexed_payload(
            event,
            status="INDEXED",
            child_count=result.child_count,
            parent_count=result.parent_count,
        )
        logger.info(
            "文档入库完成 docId=%s kbId=%s 子块=%d 父块=%d",
            event.doc_id,
            event.kb_id,
            result.child_count,
            result.parent_count,
        )
    except Exception as exc:  # 失败回写 FAILED，避免状态永久停留「处理中」
        logger.exception("文档入库失败 docId=%s", event.doc_id)
        indexed = build_indexed_payload(event, status="FAILED", message=str(exc)[:1000])
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)

    await producer.publish(config.kafka_topic_kb_indexed, indexed, key=event.doc_id)


def _ingest_sync(path: Path, event: KbIngestEvent, *, store: Any, embedder: Any) -> Any:
    """同步执行入库管道（供 :func:`asyncio.to_thread` 调用，避免事件循环阻塞）。"""
    from app.rag.ingest.pipeline import ingest_document

    return ingest_document(
        path,
        doc_id=event.doc_id,
        tenant_id=event.tenant_id,
        kb_id=event.kb_id,
        embedder=embedder,
        store=store,
    )


async def run_kb_ingest_consumer(
    *,
    config: Settings,
    producer: KafkaProducer,
    store: Any,
    embedder: Any,
    settle: Any = None,
) -> None:
    """启动入库消费循环（阻塞，供 FastAPI lifespan 拉起）。"""
    from app.messaging.kafka import consume_forever

    async def _handler(payload: dict[str, Any]) -> None:
        await handle_kb_ingest(
            payload, config=config, producer=producer, store=store, embedder=embedder
        )

    await consume_forever(
        topic=config.kafka_topic_kb_ingest,
        group_id=config.kafka_kb_consumer_group,
        bootstrap_servers=config.kafka_bootstrap_servers,
        handler=_handler,
        settle=settle,
        max_poll_interval_ms=config.kafka_max_poll_interval_ms,
        restart_backoff_seconds=config.kafka_consumer_restart_backoff_seconds,
    )


async def handle_kb_delete(payload: dict[str, Any], *, store: Any) -> None:
    """处理文档下线事件：删除该文档在向量库的全部切片。

    文档元数据与原件清理由 Java 知识域负责（下线时已同步完成），本侧只做向量清理；
    删除按 ``doc_id + tenant_id`` 过滤，避免误删其他租户数据。
    """
    doc_id = str(payload.get("doc_id", ""))
    tenant_id = str(payload.get("tenant_id", ""))
    if not doc_id or not tenant_id:
        logger.warning("忽略非法的下线事件（缺少 doc_id/tenant_id）: %s", payload)
        return
    # Milvus 删除为同步阻塞调用，放工作线程避免阻塞事件循环
    result = await asyncio.to_thread(store.drop_document, doc_id, tenant_id)
    logger.info("文档下线切片已清理 docId=%s 删除子块=%d", doc_id, result.deleted_child_count)


async def run_kb_delete_consumer(*, config: Settings, store: Any, settle: Any = None) -> None:
    """启动下线清理消费循环（阻塞，供 FastAPI lifespan 拉起）。"""
    from app.messaging.kafka import consume_forever

    async def _handler(payload: dict[str, Any]) -> None:
        await handle_kb_delete(payload, store=store)

    await consume_forever(
        topic=config.kafka_topic_kb_delete,
        group_id=config.kafka_kb_delete_consumer_group,
        bootstrap_servers=config.kafka_bootstrap_servers,
        handler=_handler,
        settle=settle,
        max_poll_interval_ms=config.kafka_max_poll_interval_ms,
        restart_backoff_seconds=config.kafka_consumer_restart_backoff_seconds,
    )
