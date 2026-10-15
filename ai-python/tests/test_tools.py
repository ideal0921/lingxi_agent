"""工具框架与工具调用循环单测（M4-4.1 ~ 4.3，离线运行，不触网 / 不连 gRPC / 不连 Kafka）。

覆盖：参数强校验（JSON Schema）、白名单注册表、执行器（超时/重试/降级/拒绝）、
全量审计（含脱敏/截断/投递失败不影响主链路）、业务工具的 schema 与结果中文化、
流式工具调用循环（直接作答 / 先调工具再作答 / 轮次上限 / 文本还原）。
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any

import pytest

from app.agent.state import ChatMessage
from app.agent.tool_loop import TOOL_ROUND_LIMIT_MESSAGE, build_tool_messages, stream_tool_answer
from app.agent.tools.audit import ToolAuditor, build_record
from app.agent.tools.business import build_business_tools, make_tool_handlers
from app.agent.tools.executor import ToolExecutor
from app.agent.tools.registry import ToolRegistry
from app.agent.tools.schema import SchemaValidationError, validate_arguments
from app.agent.tools.spec import ToolContext, ToolInvocation, ToolSpec
from app.privacy.pii import PiiMasker

ORDER_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {"order_no": {"type": "string", "minLength": 6}},
    "required": ["order_no"],
    "additionalProperties": False,
}


# ---------- 测试替身 ----------


class _RecordingAuditor(ToolAuditor):
    def __init__(self) -> None:
        super().__init__()
        self.records: list[Any] = []

    async def record(self, record) -> None:  # type: ignore[override]
        self.records.append(record)


class _Producer:
    def __init__(self, fail: bool = False) -> None:
        self.published: list[dict] = []
        self._fail = fail

    async def publish(self, topic: str, payload: dict, key: str | None = None) -> None:
        if self._fail:
            raise RuntimeError("kafka 不可用")
        self.published.append({"topic": topic, "payload": payload, "key": key})


def _spec(name: str, handler, *, schema: dict | None = None, **kwargs) -> ToolSpec:
    return ToolSpec(
        name=name,
        description=f"{name} 工具",
        parameters=schema or ORDER_SCHEMA,
        handler=handler,
        **kwargs,
    )


def _registry(*specs: ToolSpec) -> ToolRegistry:
    return ToolRegistry(specs)


# ---------- 参数强校验 ----------


class TestSchemaValidation:
    def test_passes_with_valid_arguments(self) -> None:
        assert validate_arguments(ORDER_SCHEMA, '{"order_no": "LN20240815001"}') == {
            "order_no": "LN20240815001"
        }

    def test_empty_arguments_means_empty_object(self) -> None:
        with pytest.raises(SchemaValidationError, match="required"):
            validate_arguments(ORDER_SCHEMA, "")

    @pytest.mark.parametrize(
        ("raw", "keyword"),
        [
            ("not-json", "不是合法 JSON"),
            ("[1, 2]", "必须是 JSON 对象"),
            ('{"order_no": 12345}', "is not of type"),
            ('{"order_no": "abc"}', "is too short"),
        ],
    )
    def test_rejects_invalid_arguments(self, raw: str, keyword: str) -> None:
        with pytest.raises(SchemaValidationError) as exc:
            validate_arguments(ORDER_SCHEMA, raw)
        assert keyword in str(exc.value)

    def test_rejects_undeclared_fields(self) -> None:
        """additionalProperties=false 即字段白名单：模型夹带 tenant_id 会被拒。"""
        with pytest.raises(SchemaValidationError) as exc:
            validate_arguments(ORDER_SCHEMA, '{"order_no": "LN20240815001", "tenant_id": "other"}')
        assert "tenant_id" in str(exc.value)

    def test_reports_field_location(self) -> None:
        with pytest.raises(SchemaValidationError) as exc:
            validate_arguments(ORDER_SCHEMA, '{"order_no": 1}')
        assert "order_no" in str(exc.value)


# ---------- 白名单注册表 ----------


class TestRegistry:
    async def _handler(self, args, ctx):
        return {"ok": True}

    def test_register_and_lookup(self) -> None:
        registry = _registry(_spec("get_order", self._handler))
        assert registry.names() == ["get_order"]
        assert registry.get("get_order") is not None
        assert registry.get("unknown") is None
        assert registry.is_allowed("get_order") and not registry.is_allowed("drop_table")

    def test_duplicate_name_rejected(self) -> None:
        registry = _registry(_spec("get_order", self._handler))
        with pytest.raises(ValueError, match="重名"):
            registry.register(_spec("get_order", self._handler))

    def test_schema_must_be_whitelisted_object(self) -> None:
        registry = ToolRegistry()
        with pytest.raises(ValueError, match="必须是 object"):
            registry.register(_spec("bad", self._handler, schema={"type": "string"}))
        with pytest.raises(ValueError, match="additionalProperties=false"):
            registry.register(
                _spec(
                    "bad2",
                    self._handler,
                    schema={"type": "object", "properties": {"a": {"type": "string"}}},
                )
            )

    def test_enabled_subset_acts_as_whitelist(self) -> None:
        registry = ToolRegistry(
            [_spec("get_order", self._handler), _spec("get_logistics", self._handler)],
            enabled_names=["get_order"],
        )
        assert registry.names() == ["get_order"]
        assert registry.get("get_logistics") is None

    def test_openai_tools_shape(self) -> None:
        tools = _registry(_spec("get_order", self._handler)).openai_tools()
        assert tools[0]["type"] == "function"
        assert tools[0]["function"]["name"] == "get_order"
        assert tools[0]["function"]["parameters"] == ORDER_SCHEMA


# ---------- 执行器（校验 / 超时 / 重试 / 降级 / 审计） ----------


class TestExecutor:
    async def test_success_path(self) -> None:
        calls: list[dict] = []

        async def handler(args, ctx):
            calls.append(args)
            return {"状态": "已发货", "金额": "88.50 元"}

        auditor = _RecordingAuditor()
        executor = ToolExecutor(_registry(_spec("get_order", handler)), auditor=auditor)

        outcome = await executor.execute(
            ToolInvocation(id="c1", name="get_order", arguments='{"order_no": "LN20240815002"}'),
            ToolContext(tenant_id="t-ctx"),
        )

        assert outcome.ok and outcome.attempts == 1 and not outcome.degraded
        assert json.loads(outcome.content)["状态"] == "已发货"
        assert calls == [{"order_no": "LN20240815002"}]
        assert len(auditor.records) == 1
        record = auditor.records[0]
        assert record.ok and record.tool == "get_order" and record.tenant_id == "t-ctx"
        assert record.result_chars > 0 and record.result_digest

    async def test_unregistered_tool_is_rejected_before_execution(self) -> None:
        async def handler(args, ctx):
            raise AssertionError("未注册工具不应被执行")

        auditor = _RecordingAuditor()
        executor = ToolExecutor(_registry(_spec("get_order", handler)), auditor=auditor)

        outcome = await executor.execute(
            ToolInvocation(id="c1", name="drop_table", arguments="{}"), ToolContext()
        )

        assert not outcome.ok and outcome.rejected and outcome.attempts == 0
        assert "未注册" in outcome.content
        assert auditor.records[0].rejected is True

    async def test_invalid_arguments_are_rejected_with_reason(self) -> None:
        executed = False

        async def handler(args, ctx):
            nonlocal executed
            executed = True
            return {}

        executor = ToolExecutor(_registry(_spec("get_order", handler)))
        outcome = await executor.execute(
            ToolInvocation(id="c1", name="get_order", arguments='{"order_no": "x"}'), ToolContext()
        )

        assert not outcome.ok and outcome.rejected
        assert executed is False, "参数非法时禁止执行业务动作"
        assert "参数不合法" in outcome.content

    async def test_timeout_retries_then_degrades(self) -> None:
        attempts: list[int] = []

        async def handler(args, ctx):
            attempts.append(1)
            await asyncio.sleep(0.2)
            return {"ok": True}

        executor = ToolExecutor(
            _registry(_spec("get_order", handler, timeout_seconds=0.05, max_retries=1)),
            degrade_message="业务系统暂时不可用",
        )
        outcome = await executor.execute(
            ToolInvocation(id="c1", name="get_order", arguments='{"order_no": "LN20240815001"}'),
            ToolContext(),
        )

        assert not outcome.ok and outcome.degraded
        assert outcome.attempts == 2 and len(attempts) == 2, "失败应重试 1 次"
        assert "业务系统暂时不可用" in outcome.content and "超时" in outcome.content

    async def test_retry_succeeds_on_second_attempt(self) -> None:
        calls: list[int] = []

        async def handler(args, ctx):
            calls.append(1)
            if len(calls) == 1:
                raise RuntimeError("网络抖动")
            return {"ok": True}

        executor = ToolExecutor(_registry(_spec("get_order", handler, max_retries=1)))
        outcome = await executor.execute(
            ToolInvocation(id="c1", name="get_order", arguments='{"order_no": "LN20240815001"}'),
            ToolContext(),
        )

        assert outcome.ok and outcome.attempts == 2 and not outcome.degraded

    async def test_degraded_after_exhausting_retries(self) -> None:
        async def handler(args, ctx):
            raise RuntimeError("gRPC 不可用")

        executor = ToolExecutor(_registry(_spec("get_order", handler, max_retries=0)))
        outcome = await executor.execute(
            ToolInvocation(id="c1", name="get_order", arguments='{"order_no": "LN20240815001"}'),
            ToolContext(),
        )

        assert not outcome.ok and outcome.degraded and outcome.attempts == 1
        assert "gRPC 不可用" in outcome.content

    async def test_large_result_is_truncated(self) -> None:
        async def handler(args, ctx):
            return "x" * 500

        executor = ToolExecutor(
            _registry(_spec("get_order", handler)), result_max_chars=50
        )
        outcome = await executor.execute(
            ToolInvocation(id="c1", name="get_order", arguments='{"order_no": "LN20240815001"}'),
            ToolContext(),
        )
        assert outcome.ok and len(outcome.content) < 80 and "已截断" in outcome.content


# ---------- 审计 ----------


class TestAudit:
    def test_record_masks_truncates_and_digests(self) -> None:
        """真实 PiiMasker 接入：审计入参中的手机号必须被替换为占位符。

        回归防护：此前审计误用不存在的 ``mask_text()``，脱敏静默失败后把 PII 原文
        直发 Kafka（隐私事故）。
        """
        masker = PiiMasker()
        record = build_record(
            tool="get_order",
            invocation_id="c1",
            session_id="s1",
            tenant_id="t1",
            message_id="m1",
            arguments='{"order_no": "LN20240815001", "phone": "13800001111"}',
            ok=True,
            degraded=False,
            rejected=False,
            attempts=1,
            duration_ms=12.34,
            result="x" * 5000,
            masker=masker,
        )

        assert "13800001111" not in record.arguments, "审计入参不得留存手机号原文"
        assert "[手机号1]" in record.arguments
        assert len(record.result_digest) == 16 and record.result_chars == 5000
        assert record.duration_ms == 12.3

    def test_record_without_masker_keeps_text(self) -> None:
        """无脱敏器（如无 PII 场景）时按原文记录，不抛异常。"""
        record = build_record(
            tool="get_order",
            invocation_id="c1",
            session_id="s1",
            tenant_id="t1",
            message_id="m1",
            arguments='{"order_no": "LN20240815001"}',
            ok=False,
            degraded=True,
            rejected=False,
            attempts=2,
            duration_ms=1.0,
            error="超时",
            masker=None,
        )
        assert record.arguments == '{"order_no": "LN20240815001"}'
        assert record.degraded is True and record.error == "超时"

    async def test_kafka_failure_does_not_break_tool_call(self) -> None:
        async def handler(args, ctx):
            return {"ok": True}

        executor = ToolExecutor(
            _registry(_spec("get_order", handler)),
            auditor=ToolAuditor(producer=_Producer(fail=True), topic="audit", enabled=True),
        )
        outcome = await executor.execute(
            ToolInvocation(id="c1", name="get_order", arguments='{"order_no": "LN20240815001"}'),
            ToolContext(),
        )
        assert outcome.ok, "审计投递失败不得影响工具调用结果"

    async def test_audit_event_is_published(self) -> None:
        async def handler(args, ctx):
            return {"ok": True}

        producer = _Producer()
        executor = ToolExecutor(
            _registry(_spec("get_order", handler)),
            auditor=ToolAuditor(producer=producer, topic="lingxi.tool.audit", enabled=True),
        )
        await executor.execute(
            ToolInvocation(id="c1", name="get_order", arguments='{"order_no": "LN20240815001"}'),
            ToolContext(session_id="s1"),
        )

        assert producer.published[0]["topic"] == "lingxi.tool.audit"
        assert producer.published[0]["key"] == "s1"
        assert producer.published[0]["payload"]["ok"] is True


# ---------- 业务工具（gRPC 客户端用替身，验证 schema 与结果中文化） ----------


class _FakeBusinessClient:
    """假 gRPC 客户端：记录收到的参数并返回预设结果。"""

    def __init__(self, order: dict | None = None) -> None:
        self.calls: list[tuple] = []
        self._order = order or {
            "found": True,
            "order_no": "LN20240815002",
            "status": "已发货",
            "amount": "88.50",
            "items_summary": "数据线×2、充电头×1",
            "created_at_ms": 1_725_000_000_000,
        }

    async def get_order(self, tenant_id: str, order_no: str) -> dict:
        self.calls.append(("get_order", tenant_id, order_no))
        return self._order

    async def get_logistics(self, tenant_id: str, order_no: str) -> dict:
        self.calls.append(("get_logistics", tenant_id, order_no))
        return {
            "found": True,
            "order_no": order_no,
            "carrier": "顺丰速运",
            "tracking_no": "SF1234567890",
            "status": "运输中",
            "latest_event": "快件已到达杭州转运中心",
            "updated_at_ms": 1_725_000_000_000,
        }

    async def submit_return(self, tenant_id: str, order_no: str, return_type: str, reason: str) -> dict:
        self.calls.append(("submit_return", tenant_id, order_no, return_type, reason))
        return {"ok": True, "return_id": "R-1", "status": "ACCEPTED", "reason": ""}


class TestBusinessTools:
    def _tools(self, client=None) -> tuple[dict[str, ToolSpec], _FakeBusinessClient]:
        client = client or _FakeBusinessClient()
        tools = {spec.name: spec for spec in build_business_tools(client, degrade_message="降级话术")}
        return tools, client

    def test_generated_proto_stub_is_importable(self) -> None:
        """回归防护（M4-4.2）：生成契约目录必须在 ``sys.path`` 上，stub 可导入。

        此前 ``_PROTO_DIR`` 用 ``parents[2]`` 算成 ``app/proto``（应为 ``ai-python/proto``），
        导致 ``ModuleNotFoundError: No module named 'business_pb2'``——业务工具**永远走降级**
        （模型查不到真实订单，只能转人工）。
        """
        import business_pb2 as business_pb

        assert business_pb.GetOrderRequest(tenant_id="t", order_no="LN1").order_no == "LN1"

    def test_schemas_do_not_expose_tenant(self) -> None:
        """租户由服务端注入：schema 中不得出现 tenant_id，模型传入即被拒。"""
        tools, _ = self._tools()
        for spec in tools.values():
            assert "tenant_id" not in spec.parameters["properties"]
            assert spec.parameters["additionalProperties"] is False

        with pytest.raises(SchemaValidationError):
            validate_arguments(
                tools["get_order"].parameters, '{"order_no": "LN20240815001", "tenant_id": "other"}'
            )

    def test_return_type_is_enum_constrained(self) -> None:
        tools, _ = self._tools()
        with pytest.raises(SchemaValidationError, match="is not one of"):
            validate_arguments(
                tools["submit_return"].parameters,
                '{"order_no": "LN20240815001", "return_type": "DELETE_ACCOUNT"}',
            )

    async def test_order_result_is_localized_and_uses_ctx_tenant(self) -> None:
        tools, client = self._tools()
        result = await tools["get_order"].handler(
            {"order_no": "LN20240815002"}, ToolContext(tenant_id="t-ctx")
        )

        assert client.calls[0] == ("get_order", "t-ctx", "LN20240815002")
        assert result["状态"] == "已发货" and result["金额"] == "88.50 元"
        assert result["商品"] == "数据线×2、充电头×1"

    async def test_order_not_found_returns_readable_reason(self) -> None:
        tools, _ = self._tools(_FakeBusinessClient({"found": False, "reason": "订单不存在或不属于当前租户"}))
        result = await tools["get_order"].handler({"order_no": "LN00000000000"}, ToolContext(tenant_id="t"))
        assert result["found"] is False and "不属于当前租户" in result["说明"]

    async def test_logistics_result_is_localized(self) -> None:
        tools, _ = self._tools()
        result = await tools["get_logistics"].handler({"order_no": "LN20240815002"}, ToolContext(tenant_id="t-ctx"))
        assert result["物流状态"] == "运输中" and "杭州转运中心" in result["最新动态"]

    async def test_submit_return_success_and_failure(self) -> None:
        tools, _ = self._tools()

        ok = await tools["submit_return"].handler(
            {"order_no": "LN20240815002", "return_type": "REFUND", "reason": "不喜欢"},
            ToolContext(tenant_id="t-ctx"),
        )
        assert ok["ok"] is True and ok["受理单号"] == "R-1" and ok["类型"] == "仅退款"

        class _Rejecting(_FakeBusinessClient):
            async def submit_return(self, tenant_id, order_no, return_type, reason):
                return {"ok": False, "reason": "订单状态不允许退换货"}

        tools2, _ = self._tools(_Rejecting())
        bad = await tools2["submit_return"].handler(
            {"order_no": "LN1", "return_type": "EXCHANGE", "reason": ""}, ToolContext(tenant_id="t")
        )
        assert bad["ok"] is False and "不允许" in bad["说明"]

    def test_handlers_are_bound_to_client_by_closure(self) -> None:
        client_a, client_b = _FakeBusinessClient(), _FakeBusinessClient()
        handlers_a = make_tool_handlers(client_a)
        handlers_b = make_tool_handlers(client_b)
        assert handlers_a["get_order"] is not handlers_b["get_order"], "处理器须各自绑定客户端实例"


# ---------- 流式工具调用循环 ----------


@dataclass(slots=True)
class _Call:
    id: str
    name: str
    arguments: str


@dataclass(slots=True)
class _Event:
    delta: str = ""
    tool_call: _Call | None = None


class _FakeGateway:
    """按脚本逐轮返回事件；记录收到的 tools 与 messages。"""

    def __init__(self, rounds: list[list[_Event]]) -> None:
        self._rounds = rounds
        self.calls: list[dict] = []

    async def stream_events(self, messages, tools=None, model=None):
        self.calls.append({"messages": [dict(m) for m in messages], "tools": tools})
        round_events = self._rounds[min(len(self.calls) - 1, len(self._rounds) - 1)]
        for event in round_events:
            yield event


class _ToolExecutorStub:
    def __init__(self) -> None:
        self.executed: list[ToolInvocation] = []

    async def execute(self, invocation, ctx):
        from app.agent.tools.spec import ToolOutcome

        self.executed.append(invocation)
        return ToolOutcome(
            invocation_id=invocation.id,
            name=invocation.name,
            ok=True,
            content='{"状态": "已发货"}',
            duration_ms=3.0,
        )


def _tool_registry_stub() -> ToolRegistry:
    async def handler(args, ctx):
        return {}

    return _registry(_spec("get_order", handler))


class TestToolLoop:
    def _source(self, gateway, *, max_rounds=3, executor=None):
        """未还原的原始文本流（graph.py 中的工具分支由单条还原管道包住它）。"""
        return stream_tool_answer(
            gateway=gateway,
            messages=[{"role": "user", "content": "包裹到哪了"}],
            registry=_tool_registry_stub(),
            executor=executor or _ToolExecutorStub(),
            ctx=ToolContext(tenant_id="t-ctx", session_id="s1"),
            max_rounds=max_rounds,
        )

    async def _collect(self, gateway, *, max_rounds=3, executor=None) -> str:
        return "".join([piece async for piece in self._source(gateway, max_rounds=max_rounds, executor=executor)])

    async def _collect_restored(self, gateway, masker, *, max_rounds=3, executor=None) -> str:
        """按生产接线方式收集：**单条** ``restore_stream`` 管道包住整条工具流。"""
        pieces = masker.restore_stream(self._source(gateway, max_rounds=max_rounds, executor=executor))
        return "".join([piece async for piece in pieces])

    async def test_direct_answer_has_no_tool_round(self) -> None:
        gateway = _FakeGateway([[_Event(delta="你好"), _Event(delta="，有什么可以帮您")]])
        executor = _ToolExecutorStub()

        text = await self._collect(gateway, executor=executor)

        assert text == "你好，有什么可以帮您"
        assert executor.executed == []
        assert gateway.calls[0]["tools"], "应把工具定义下发给模型"

    async def test_tool_call_then_answer(self) -> None:
        gateway = _FakeGateway(
            [
                [_Event(tool_call=_Call(id="c1", name="get_order", arguments='{"order_no": "LN1"}'))],
                [_Event(delta="您的订单已发货")],
            ]
        )
        executor = _ToolExecutorStub()

        text = await self._collect(gateway, executor=executor)

        assert text == "您的订单已发货"
        assert [invocation.name for invocation in executor.executed] == ["get_order"]
        # 回填：assistant(tool_calls) + tool(result) 一并进入第二轮请求
        second = gateway.calls[1]["messages"]
        assert second[-2]["role"] == "assistant" and second[-2]["tool_calls"][0]["function"]["name"] == "get_order"
        assert second[-1]["role"] == "tool" and second[-1]["tool_call_id"] == "c1"

    async def test_parallel_tool_calls_in_one_round(self) -> None:
        gateway = _FakeGateway(
            [
                [
                    _Event(tool_call=_Call(id="c1", name="get_order", arguments='{"order_no": "LN1"}')),
                    _Event(tool_call=_Call(id="c2", name="get_order", arguments='{"order_no": "LN2"}')),
                ],
                [_Event(delta="两个订单都已发货")],
            ]
        )
        executor = _ToolExecutorStub()

        text = await self._collect(gateway, executor=executor)

        assert text == "两个订单都已发货"
        assert [invocation.id for invocation in executor.executed] == ["c1", "c2"]

    async def test_round_limit_stops_loop(self) -> None:
        always_tool = [
            [_Event(tool_call=_Call(id=f"c{i}", name="get_order", arguments='{"order_no": "LN1"}'))]
            for i in range(5)
        ]
        gateway = _FakeGateway(always_tool)
        executor = _ToolExecutorStub()

        text = await self._collect(gateway, max_rounds=2, executor=executor)

        assert text == TOOL_ROUND_LIMIT_MESSAGE
        assert len(executor.executed) == 1, "达到上限后不再执行新的工具调用"
        assert len(gateway.calls) == 2

    async def test_restore_pipeline_keeps_placeholder_intact_across_deltas(self) -> None:
        """回归防护：占位符被切在两个相邻 delta 之间，单条持久管道仍能完整还原。

        此前逐片段还原（``_restore_pieces``）会把 ``[手机号1]`` 切成 ``[手机`` + ``号1]``，
        两段都不匹配占位符，用户端看到的是占位符残片。
        """
        masker = PiiMasker()
        masker.mask("我的手机号是13800001111")  # 注册占位符 [手机号1]
        gateway = _FakeGateway([[_Event(delta="您的手机号 [手机"), _Event(delta="号1] 已登记")]])

        text = await self._collect_restored(gateway, masker)

        assert "13800001111" in text, "跨片段占位符必须被还原为原文"
        assert "[手机号" not in text and "号1]" not in text

    async def test_restore_pipeline_spans_tool_rounds(self) -> None:
        """还原缓冲跨工具轮次共享：第二轮（工具回填后）回答中的占位符同样被还原。"""
        masker = PiiMasker()
        masker.mask("我的手机号是13800001111")
        gateway = _FakeGateway(
            [
                [_Event(tool_call=_Call(id="c1", name="get_order", arguments='{"order_no": "LN1"}'))],
                [_Event(delta="已为您登记 [手机号"), _Event(delta="1]，请留意短信")],
            ]
        )

        text = await self._collect_restored(gateway, masker)

        assert "13800001111" in text and "[手机号" not in text

    def test_build_tool_messages_layout(self) -> None:
        messages = build_tool_messages(
            system_prompt="你是客服",
            knowledge_text="【知识片段】退款 3-7 天",
            summary="用户此前咨询退款",
            history=[ChatMessage(role="user", content="退款多久"), ChatMessage(role="agent", content="3-7 天")],
            query="那换货呢",
        )

        assert messages[0] == {"role": "system", "content": "你是客服"}
        assert "【会话摘要】" in messages[1]["content"] and "退款 3-7 天" in messages[1]["content"]
        assert messages[2] == {"role": "user", "content": "退款多久"}
        assert messages[3] == {"role": "assistant", "content": "3-7 天"}
        assert messages[-1] == {"role": "user", "content": "那换货呢"}
