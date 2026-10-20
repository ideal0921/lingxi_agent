"""Kafka 链路透传单测（M5-5.9）：消费提取 trace_id、生产注入 trace_id。

全部离线运行：消费用假消费者按脚本产出消息（不连真实 Kafka），生产用假底层
producer 捕获消息体；span 落库用假 writer 捕获。
"""

from __future__ import annotations

import asyncio
import json

import app.messaging.kafka as kafka_module
from app.messaging.kafka import KafkaProducer, consume_forever
from app.trace.context import bind, current, reset


class _Record:
    def __init__(self, value: str, offset: int = 0) -> None:
        self.value = value.encode("utf-8")
        self.offset = offset


class _FakeConsumer:
    def __init__(self, records: list[_Record] | None = None) -> None:
        self._records = list(records or [])

    async def start(self) -> None:
        pass

    async def stop(self) -> None:
        pass

    def __aiter__(self):
        async def _gen():
            for record in self._records:
                yield record

        return _gen()


class _FakeWriter:
    def __init__(self) -> None:
        self.spans: list = []

    def record(self, span: object) -> None:
        self.spans.append(span)


def _install_writer(monkeypatch) -> _FakeWriter:
    writer = _FakeWriter()
    monkeypatch.setattr(kafka_module, "get_writer", lambda: writer)
    return writer


def _install_consumer(monkeypatch, records: list[_Record]) -> None:
    monkeypatch.setattr(kafka_module, "AIOKafkaConsumer", lambda *a, **k: _FakeConsumer(records))


class _FakeLowProducer:
    """假底层 producer：捕获发送的消息体（value 字节）。"""

    def __init__(self) -> None:
        self.sent: list[tuple[str, dict]] = []

    async def send_and_wait(self, topic, value=None, key=None) -> None:
        self.sent.append((topic, json.loads(value.decode("utf-8"))))


class TestConsumeTrace:
    async def test_binds_trace_and_records_span(self, monkeypatch) -> None:
        writer = _install_writer(monkeypatch)
        _install_consumer(
            monkeypatch,
            [_Record(json.dumps({"trace_id": "t-consume", "tenant_id": "tn-1", "content": "hi"}))],
        )
        seen: dict[str, str] = {}

        async def handler(payload: dict) -> None:
            seen["trace"] = current().trace_id

        async def settle() -> None:
            await asyncio.sleep(0.05)

        async with asyncio.timeout(3):
            await consume_forever(
                topic="lingxi.session.message",
                group_id="g",
                bootstrap_servers="x",
                handler=handler,
                settle=settle,
                restart_backoff_seconds=0.01,
            )

        assert seen["trace"] == "t-consume", "handler 内应能读到消费侧绑定的 trace_id"
        # 消费迭代结束会重建消费者（既有语义），同一消息可能被处理多次；
        # 断言所有 span 均为同一链路且字段正确即可。
        assert writer.spans
        assert all(span.trace_id == "t-consume" for span in writer.spans)
        span = writer.spans[0]
        assert span.component == "KAFKA"
        assert span.entry == "lingxi.session.message"
        assert span.tenant_id == "tn-1"
        assert span.status == "OK"

    async def test_missing_trace_id_is_noop_and_no_error(self, monkeypatch) -> None:
        """缺 trace_id 字段不得报错，也不记 span（保持既有消费语义）。"""
        writer = _install_writer(monkeypatch)
        _install_consumer(monkeypatch, [_Record(json.dumps({"content": "hi"}))])
        called = {"n": 0}

        async def handler(payload: dict) -> None:
            called["n"] += 1

        async def settle() -> None:
            await asyncio.sleep(0.05)

        async with asyncio.timeout(3):
            await consume_forever(
                topic="lingxi.session.message",
                group_id="g",
                bootstrap_servers="x",
                handler=handler,
                settle=settle,
                restart_backoff_seconds=0.01,
            )

        assert called["n"] >= 1
        assert writer.spans == []

    async def test_handler_failure_records_error_span(self, monkeypatch) -> None:
        """handler 抛异常时仍记 span 且状态为 ERROR，消费循环不中断。"""
        writer = _install_writer(monkeypatch)
        _install_consumer(
            monkeypatch,
            [
                _Record(json.dumps({"trace_id": "t-err"})),
                _Record(json.dumps({"trace_id": "t-ok"})),
            ],
        )

        async def handler(payload: dict) -> None:
            if payload["trace_id"] == "t-err":
                raise RuntimeError("handler boom")

        async def settle() -> None:
            await asyncio.sleep(0.08)

        async with asyncio.timeout(3):
            await consume_forever(
                topic="lingxi.session.message",
                group_id="g",
                bootstrap_servers="x",
                handler=handler,
                settle=settle,
                restart_backoff_seconds=0.01,
            )

        by_trace = {span.trace_id: span for span in writer.spans}
        assert by_trace["t-err"].status == "ERROR"
        assert "boom" in by_trace["t-err"].error_msg
        assert by_trace["t-ok"].status == "OK", "失败消息不得影响后续消息处理"


class TestProduceTrace:
    async def test_publish_injects_trace_id_from_context(self) -> None:
        producer = KafkaProducer("x")
        producer._producer = _FakeLowProducer()
        tokens = bind("trace-produce")
        try:
            await producer.publish("lingxi.ai.reply", {"answer": "hi"}, key="s-1")
        finally:
            reset(tokens)
        assert producer._producer.sent[0][1]["trace_id"] == "trace-produce"

    async def test_publish_without_context_adds_no_field(self) -> None:
        producer = KafkaProducer("x")
        producer._producer = _FakeLowProducer()
        await producer.publish("lingxi.ai.chunk", {"delta": "hi"})
        assert "trace_id" not in producer._producer.sent[0][1]

    async def test_publish_does_not_mutate_caller_payload(self) -> None:
        producer = KafkaProducer("x")
        producer._producer = _FakeLowProducer()
        payload = {"answer": "hi"}
        tokens = bind("trace-copy")
        try:
            await producer.publish("lingxi.ai.reply", payload)
        finally:
            reset(tokens)
        assert "trace_id" not in payload, "不得修改调用方传入的字典"
        assert producer._producer.sent[0][1]["trace_id"] == "trace-copy"
