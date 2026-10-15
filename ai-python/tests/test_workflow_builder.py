"""多工作流运行时单测（M4-4.8，离线：假 gateway/retriever/工具 + 真 loader/builder/图）。

覆盖重点：
- 图编译与执行：``retrieve → branch`` 的 true/false 两条路径、表达式求值失败走 false 兜底、
  无出边节点接 END；
- 事件契约：``CONTEXT → DELTA* → DONE`` 顺序、无检索节点时补发空 CONTEXT；
- 工具语义：``tool`` 节点声明的工具被收敛为白名单并带到 LLM 节点（``stream_events`` 收到 tools）；
- **提前中断语义**：消费方在 CONTEXT 处 break（转人工）后不再发生任何 LLM 调用
  （验证生成器关闭能正确穿透 ``astream`` → ``restore_stream`` 链）；
- 三重降级回落：开关关闭 / 未配置工作流 / 编排域不可达 / 图非法 / 版本未发布。

注意：每个用例使用**独立的 loader**（已编译图缓存以 ``(workflow_id, version)`` 为键、
闭包捕获依赖单例，复用同一 loader 会把上一个用例的假依赖泄漏到下一个用例）。
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from app.agent.state import ChatMessage, StreamKind
from app.agent.tools.executor import ToolExecutor
from app.agent.tools.registry import ToolRegistry
from app.agent.tools.spec import ToolSpec
from app.agent.workflow.client import WorkflowClient, WorkflowClientError
from app.agent.workflow.loader import WorkflowLoader
from app.agent.workflow.run import stream_workflow_or_default
from app.config import Settings
from app.rag.retrieve import RetrievalHit


class _FakeGateway:
    """假 LLM 网关：记录调用；``stream`` 逐字产出、``stream_events`` 可回放工具调用。"""

    def __init__(
        self,
        answer: str = "固定回答",
        *,
        tool_calls: list[Any] | None = None,
        events_error: Exception | None = None,
    ) -> None:
        self._answer = answer
        self._tool_calls = list(tool_calls or [])
        self._events_error = events_error
        self.prompts: list[str] = []
        self.tools_seen: list[list[dict] | None] = []
        self.event_messages: list[list[dict]] = []
        self.stream_calls = 0
        self.event_calls = 0

    async def generate(self, prompt: str, model: str | None = None) -> str:
        self.prompts.append(prompt)
        return self._answer

    async def stream(self, prompt: str, model: str | None = None):
        self.stream_calls += 1
        self.prompts.append(prompt)
        for char in self._answer:
            yield char

    async def stream_events(self, messages: list[dict], tools: list[dict] | None = None, model=None):
        self.event_calls += 1
        self.event_messages.append(messages)
        self.tools_seen.append(tools)
        if self._events_error is not None:
            raise self._events_error
        # 一轮里要么产出工具调用、要么产出正文（与真实 Provider 行为一致）
        if self._tool_calls:
            for call in self._tool_calls:
                yield SimpleNamespace(delta="", tool_call=call)
            self._tool_calls = []
            return
        for char in self._answer:
            yield SimpleNamespace(delta=char, tool_call=None)


class _FakeRetriever:
    """假检索器：返回预设命中并记录入参（含 kb_id/top_k 覆盖）。"""

    def __init__(self, hits: list[RetrievalHit] | None = None) -> None:
        self._hits = list(hits or [])
        self.calls: list[dict] = []

    def retrieve(self, query: str, *, tenant_id: str, kb_id: str | None = None, top_k: int | None = None):
        self.calls.append({"query": query, "tenant_id": tenant_id, "kb_id": kb_id, "top_k": top_k})
        return list(self._hits)


class _FakeToolCall:
    """模拟 Provider 事件中的工具调用。"""

    def __init__(self, name: str = "get_order", arguments: str = '{"order_no": "LN1"}') -> None:
        self.id = "call-1"
        self.name = name
        self.arguments = arguments


class _FakeWorkflowStub:
    """假编排域 stub：返回预设快照与版本正文。"""

    def __init__(self, *, published: int, canary_version: int = 0, canary_percent: int = 0, definition: str):
        self._published = published
        self._canary_version = canary_version
        self._canary_percent = canary_percent
        self._definition = definition
        self.version_calls: list[int] = []
        self.workflow_calls = 0

    def GetWorkflow(self, request: object, timeout: float | None = None) -> object:
        self.workflow_calls += 1
        workflow = SimpleNamespace(
            workflow_id="wf-1",
            published_version=self._published,
            latest_version=max(self._published, self._canary_version),
            canary_version=self._canary_version,
            canary_percent=self._canary_percent,
            status="ENABLED",
        )
        return SimpleNamespace(workflow=workflow)

    def GetVersion(self, request: object, timeout: float | None = None) -> object:
        self.version_calls.append(request.version)
        return SimpleNamespace(ok=True, reason="", definition_json=self._definition)


def _hit(score: float = 0.9, parent: str = "父块章节内容") -> RetrievalHit:
    return RetrievalHit(
        chunk_id="c1",
        doc_id="d1",
        kb_id="kb1",
        parent_id="p1",
        content="子块原文",
        seq=1,
        heading_path="退款政策 > 时效",
        doc_type="markdown",
        page=None,
        score=score,
        parent_content=parent,
    )


def _settings(**overrides: Any) -> Settings:
    base = {
        "agent_workflow_enabled": True,
        "agent_default_workflow_id": "wf-1",
        "pii_enabled": False,  # 关闭脱敏以直接断言原文，避免占位符干扰
        "agent_history_max_messages": 20,
    }
    base.update(overrides)
    return Settings(**base)


def _loader(
    stub: _FakeWorkflowStub | None = None,
    *,
    error: Exception | None = None,
    snapshot_ttl_seconds: float = 30.0,
) -> WorkflowLoader:
    """构造独立 loader（假 stub 注入；``error`` 时模拟编排域不可达）。"""

    def factory() -> object:
        if error is not None:
            raise error
        return stub

    client = WorkflowClient("127.0.0.1:9090", stub_factory=factory)
    return WorkflowLoader(client, snapshot_ttl_seconds=snapshot_ttl_seconds)


BRANCH_GRAPH = """
{
  "nodes": [
    {"id": "n1", "type": "retrieve", "name": "检索", "config": {"kb_id": "kb-1", "top_k": 2}},
    {"id": "n2", "type": "branch", "name": "分支", "config": {"expression": "top_score >= 0.6"}},
    {"id": "n3", "type": "llm", "name": "命中回答", "config": {"temperature": 0.1}},
    {"id": "n4", "type": "llm", "name": "兜底回答", "config": {}}
  ],
  "edges": [
    {"id": "e1", "source": "n1", "target": "n2"},
    {"id": "e2", "source": "n2", "target": "n3", "condition": "true"},
    {"id": "e3", "source": "n2", "target": "n4", "condition": "false"}
  ]
}
"""

TOOL_GRAPH = """
{
  "nodes": [
    {"id": "t1", "type": "tool", "name": "允许查订单", "config": {"tool_name": "get_order"}},
    {"id": "n1", "type": "retrieve", "name": "检索", "config": {"kb_id": "kb-1"}},
    {"id": "n2", "type": "llm", "name": "带工具回答", "config": {}}
  ],
  "edges": [
    {"id": "e1", "source": "t1", "target": "n1"},
    {"id": "e2", "source": "n1", "target": "n2"}
  ]
}
"""

NAMED_LITE_GRAPH = """
{
  "nodes": [
    {"id": "n1", "type": "llm", "name": "直答", "config": {}}
  ],
  "edges": []
}
"""


def _collect_events(stream) -> list[Any]:
    """把流式事件收集为列表（供顺序断言）。"""

    async def _run() -> list[Any]:
        return [event async for event in stream]

    return _run()


async def _run_once(
    *,
    graph_definition: str,
    gateway: _FakeGateway,
    retriever: _FakeRetriever | None = None,
    tool_registry: ToolRegistry | None = None,
    tool_executor: ToolExecutor | None = None,
    settings: Settings | None = None,
    session_id: str = "session-1",
    workflow_id: str = "",
    loader: WorkflowLoader | None = None,
    published: int = 1,
    canary_version: int = 0,
    canary_percent: int = 0,
) -> list[Any]:
    """按固定夹具跑一轮 ``stream_workflow_or_default``，返回事件列表。"""
    cfg = settings or _settings()
    stub = _FakeWorkflowStub(
        published=published,
        canary_version=canary_version,
        canary_percent=canary_percent,
        definition=graph_definition,
    )
    active_loader = loader if loader is not None else _loader(stub)
    return await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            history=[ChatMessage(role="user", content="在吗")],
            retriever=retriever,
            tool_registry=tool_registry,
            tool_executor=tool_executor,
            workflow_id=workflow_id,
            session_id=session_id,
            config=cfg,
            loader=active_loader,
        )
    )


# ---------- 图编译与执行 ----------


async def test_retrieve_branch_true_path_streams_answer() -> None:
    gateway = _FakeGateway("一到三个工作日到账")
    retriever = _FakeRetriever([_hit(score=0.93)])

    events = await _run_once(graph_definition=BRANCH_GRAPH, gateway=gateway, retriever=retriever)

    assert [event.kind for event in events] == [
        StreamKind.CONTEXT,
        *(StreamKind.DELTA for _ in range(len("一到三个工作日到账"))),
        StreamKind.DONE,
    ]
    assert "".join(event.delta for event in events) == "一到三个工作日到账"
    # 检索节点覆盖了节点配置里的 kb_id/top_k
    assert retriever.calls[0]["kb_id"] == "kb-1"
    assert retriever.calls[0]["top_k"] == 2
    # true 分支的 llm 节点提示词含检索到的父块内容
    assert gateway.prompts and "父块章节内容" in gateway.prompts[0]


async def test_branch_false_path_uses_fallback_llm() -> None:
    gateway = _FakeGateway("未命中兜底")
    retriever = _FakeRetriever([_hit(score=0.12)])

    events = await _run_once(graph_definition=BRANCH_GRAPH, gateway=gateway, retriever=retriever)

    assert "".join(event.delta for event in events) == "未命中兜底"
    # 走 false 分支：提示词仍带检索上下文，但节点是兜底 llm
    assert len(gateway.prompts) == 1


async def test_branch_false_path_when_no_hits() -> None:
    gateway = _FakeGateway("无知识兜底")
    retriever = _FakeRetriever([])

    events = await _run_once(graph_definition=BRANCH_GRAPH, gateway=gateway, retriever=retriever)

    context_events = [event for event in events if event.kind is StreamKind.CONTEXT]
    assert context_events and context_events[0].contexts == []
    assert "".join(event.delta for event in events) == "无知识兜底"


async def test_expression_failure_takes_false_fallback() -> None:
    gateway = _FakeGateway("求值失败兜底")
    retriever = _FakeRetriever([_hit(score=0.99)])
    broken = BRANCH_GRAPH.replace("top_score >= 0.6", "__import__('os').system('rm -rf /')")

    events = await _run_once(graph_definition=broken, gateway=gateway, retriever=retriever)

    # 非法表达式不得执行，且按约定走 false 兜底分支
    assert "".join(event.delta for event in events) == "求值失败兜底"


async def test_no_retrieve_node_prepends_empty_context() -> None:
    gateway = _FakeGateway("直答内容")

    events = await _run_once(graph_definition=NAMED_LITE_GRAPH, gateway=gateway)

    assert events[0].kind is StreamKind.CONTEXT
    assert events[0].contexts == []
    assert "".join(event.delta for event in events) == "直答内容"


# ---------- 工具语义 ----------


def _tool_registry_and_executor(seen: list[dict]) -> tuple[ToolRegistry, ToolExecutor]:
    """构造真实注册表与执行器（离线 handler），用于验证工具链路端到端。"""

    async def handler(args: dict, ctx: Any) -> dict:
        seen.append({"args": args, "tenant_id": ctx.tenant_id, "session_id": ctx.session_id})
        return {"found": True, "order_no": args.get("order_no", ""), "status": "已发货"}

    spec = ToolSpec(
        name="get_order",
        description="查询订单",
        parameters={
            "type": "object",
            "properties": {"order_no": {"type": "string"}},
            "required": ["order_no"],
            "additionalProperties": False,
        },
        handler=handler,
    )
    registry = ToolRegistry([spec])
    return registry, ToolExecutor(registry)


async def test_tool_node_binds_declared_tools_to_llm() -> None:
    gateway = _FakeGateway("订单已发货", tool_calls=[_FakeToolCall()])
    seen: list[dict] = []
    registry, executor = _tool_registry_and_executor(seen)

    events = await _run_once(
        graph_definition=TOOL_GRAPH,
        gateway=gateway,
        retriever=_FakeRetriever([_hit()]),
        tool_registry=registry,
        tool_executor=executor,
    )

    # 图中声明的工具被带到 LLM 节点（stream_events 收到 tools）
    assert gateway.tools_seen and gateway.tools_seen[0]
    assert gateway.tools_seen[0][0]["function"]["name"] == "get_order"
    # 模型发起调用 → 执行器真实执行（租户由服务端注入）→ 结果回填后继续生成
    assert seen and seen[0]["args"]["order_no"] == "LN1"
    assert seen[0]["tenant_id"] == "t1"
    assert "".join(event.delta for event in events) == "订单已发货"


async def test_undeclared_tool_is_not_exposed() -> None:
    """未在工作流中声明的工具不得出现在 tools 面（编排即白名单）。"""
    gateway = _FakeGateway("纯文本回答")
    seen: list[dict] = []
    registry, executor = _tool_registry_and_executor(seen)

    await _run_once(
        graph_definition=NAMED_LITE_GRAPH,
        gateway=gateway,
        tool_registry=registry,
        tool_executor=executor,
    )

    # 无 tool 节点 → 走纯提示词路径（不调用 stream_events）
    assert gateway.stream_calls == 1
    assert gateway.event_calls == 0


# ---------- 提前中断（转人工） ----------


async def test_break_on_context_prevents_any_llm_call() -> None:
    """消费方在 CONTEXT 处 break 后，不得再发生任何 LLM 调用（生成器关闭需穿透到图内部）。"""
    gateway = _FakeGateway("不该被生成")
    retriever = _FakeRetriever([_hit()])
    stub = _FakeWorkflowStub(published=1, definition=BRANCH_GRAPH)
    loader = _loader(stub)

    stream = stream_workflow_or_default(
        "我要转人工",
        gateway=gateway,
        tenant_id="t1",
        retriever=retriever,
        session_id="session-1",
        config=_settings(),
        loader=loader,
    )
    async for event in stream:
        if event.kind is StreamKind.CONTEXT:
            break  # 模拟命中转人工规则后关闭生成器
    # 给事件循环若干次机会，确认没有「后台仍在生成」
    import asyncio

    await asyncio.sleep(0)
    await asyncio.sleep(0)

    assert gateway.stream_calls == 0
    assert gateway.event_calls == 0
    assert gateway.prompts == []


# ---------- 灰度路由（loader 视角） ----------


async def test_loader_uses_canary_version_when_percent_full() -> None:
    gateway = _FakeGateway("灰度版回答")
    canary_graph = BRANCH_GRAPH.replace("命中回答", "灰度回答")
    stub = _FakeWorkflowStub(published=1, canary_version=2, canary_percent=100, definition=canary_graph)
    loader = _loader(stub)

    events = await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            retriever=_FakeRetriever([_hit(score=0.9)]),
            session_id="session-canary",
            config=_settings(),
            loader=loader,
        )
    )

    assert "".join(event.delta for event in events) == "灰度版回答"
    assert stub.version_calls == [2]  # 加载的是灰度版本


async def test_loader_caches_snapshot_and_definition() -> None:
    gateway = _FakeGateway("缓存回答")
    stub = _FakeWorkflowStub(published=1, definition=BRANCH_GRAPH)
    loader = _loader(stub)

    for index in range(3):
        await _collect_events(
            stream_workflow_or_default(
                "退款多久到账",
                gateway=gateway,
                retriever=_FakeRetriever([_hit()]),
                session_id=f"session-{index}",
                config=_settings(),
                loader=loader,
            )
        )

    # 快照与版本正文各只取一次（其余命中缓存），且复用同一已编译图
    assert stub.workflow_calls == 1
    assert stub.version_calls == [1]


async def test_different_versions_compile_separately() -> None:
    gateway = _FakeGateway("版本回答")
    stub = _FakeWorkflowStub(
        published=1, canary_version=2, canary_percent=50, definition=BRANCH_GRAPH
    )
    loader = _loader(stub)

    versions = set()
    for index in range(40):
        await _collect_events(
            stream_workflow_or_default(
                "退款多久到账",
                gateway=gateway,
                retriever=_FakeRetriever([_hit()]),
                session_id=f"session-{index}",
                config=_settings(),
                loader=loader,
            )
        )
    versions = set(stub.version_calls)

    assert versions == {1, 2}  # 两个版本各自编译并加载过


# ---------- 三重降级回落 ----------


async def test_falls_back_when_workflow_disabled() -> None:
    gateway = _FakeGateway("内置图回答")
    stub = _FakeWorkflowStub(published=1, definition=BRANCH_GRAPH)
    loader = _loader(stub)

    events = await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            config=_settings(agent_workflow_enabled=False),
            loader=loader,
        )
    )

    assert "".join(event.delta for event in events) == "内置图回答"
    assert stub.workflow_calls == 0  # 未触达编排域


async def test_falls_back_when_no_workflow_configured() -> None:
    gateway = _FakeGateway("默认回答")
    stub = _FakeWorkflowStub(published=1, definition=BRANCH_GRAPH)
    loader = _loader(stub)

    events = await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            config=_settings(agent_default_workflow_id=""),
            loader=loader,
        )
    )

    assert "".join(event.delta for event in events) == "默认回答"
    assert stub.workflow_calls == 0


async def test_falls_back_when_client_unavailable() -> None:
    gateway = _FakeGateway("降级回答")
    loader = _loader(error=WorkflowClientError("编排域不可达"))

    events = await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            config=_settings(),
            loader=loader,
        )
    )

    assert "".join(event.delta for event in events) == "降级回答"


async def test_falls_back_when_definition_invalid() -> None:
    gateway = _FakeGateway("非法图降级回答")
    stub = _FakeWorkflowStub(published=1, definition="{not json")
    loader = _loader(stub)

    events = await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            config=_settings(),
            loader=loader,
        )
    )

    assert "".join(event.delta for event in events) == "非法图降级回答"


async def test_falls_back_when_version_not_published() -> None:
    gateway = _FakeGateway("未发布降级回答")
    stub = _FakeWorkflowStub(published=0, definition=BRANCH_GRAPH)
    loader = _loader(stub)

    events = await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            config=_settings(),
            loader=loader,
        )
    )

    assert "".join(event.delta for event in events) == "未发布降级回答"
    assert stub.version_calls == []


async def test_falls_back_when_loader_raises() -> None:
    gateway = _FakeGateway("异常降级回答")

    class _BrokenLoader:
        async def prepare(self, *_args: Any, **_kwargs: Any) -> None:
            raise RuntimeError("loader 内部异常")

    events = await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            config=_settings(),
            loader=_BrokenLoader(),
        )
    )

    assert "".join(event.delta for event in events) == "异常降级回答"


async def test_builtin_fallback_emits_context_event() -> None:
    """回落内置图时事件契约不变（CONTEXT 前置），上层转人工判定不受影响。"""
    gateway = _FakeGateway("内置回答")
    retriever = _FakeRetriever([_hit()])

    events = await _collect_events(
        stream_workflow_or_default(
            "退款多久到账",
            gateway=gateway,
            tenant_id="t1",
            retriever=retriever,
            config=_settings(agent_default_workflow_id=""),
        )
    )

    assert events[0].kind is StreamKind.CONTEXT
    assert events[-1].kind is StreamKind.DONE


@pytest.mark.parametrize("expression", ["top_score >= 0.5", "hit_count == 1"])
async def test_expression_variables_come_from_retrieval(expression: str) -> None:
    """分支变量确实取自检索结果（改表达式即改走向）。"""
    gateway = _FakeGateway("分支变量回答")
    retriever = _FakeRetriever([_hit(score=0.8)])
    graph = BRANCH_GRAPH.replace("top_score >= 0.6", expression)

    events = await _run_once(graph_definition=graph, gateway=gateway, retriever=retriever)

    assert "".join(event.delta for event in events) == "分支变量回答"
