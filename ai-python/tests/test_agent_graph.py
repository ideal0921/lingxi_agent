"""Agent 最小图单测（M3-3.7，离线运行，不连 Milvus / 不触网）。

覆盖：节点编排（加载上下文 → 检索 → 生成）、提示词拼装（知识片段/历史/问题）、
上下文裁剪、父块优先、检索失败降级不阻断、无检索器场景，以及 create_agent 装配。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from app.agent.graph import build_graph, build_prompt, create_agent, format_context, stream_answer
from app.agent.state import AgentState, ChatMessage, StreamKind
from app.agent.tools.executor import ToolExecutor
from app.agent.tools.registry import ToolRegistry
from app.agent.tools.spec import ToolContext, ToolSpec
from app.config import Settings
from app.rag.retrieve import RetrievalHit, Retriever


class _FakeGateway:
    """记录提示词并返回固定答案的假 LLM 网关。"""

    def __init__(self, answer: str = "固定回答") -> None:
        self._answer = answer
        self.prompts: list[str] = []

    async def generate(self, prompt: str, model: str | None = None) -> str:
        self.prompts.append(prompt)
        return self._answer


class _FakeRetriever:
    """按预设返回命中或抛错的假检索器。"""

    def __init__(self, hits: list[RetrievalHit] | None = None, error: Exception | None = None) -> None:
        self._hits = list(hits or [])
        self._error = error
        self.calls: list[dict] = []

    def retrieve(self, query: str, *, tenant_id: str, kb_id: str | None = None, top_k: int | None = None):
        self.calls.append({"query": query, "tenant_id": tenant_id, "kb_id": kb_id})
        if self._error is not None:
            raise self._error
        return list(self._hits)


class _FakeStore:
    """实现 ChunkSearchStore 最小能力的假存储（供 create_agent 装配）。"""

    def __init__(self, rows: list[dict]) -> None:
        self._rows = rows

    def search_chunks(
        self,
        *,
        dense: Sequence[float],
        sparse: Mapping[int, float] | None,
        tenant_id: str,
        kb_id: str | None = None,
        limit: int = 20,
    ) -> list[dict]:
        return self._rows

    def fetch_parent_content(self, parent_id: str, tenant_id: str) -> str:
        return f"父块:{parent_id}"


def _hit(
    heading: str = "退款政策 > 时效",
    content: str = "子块原文",
    parent: str = "父块章节内容",
    doc_id: str = "d1",
    score: float = 0.9,
) -> RetrievalHit:
    return RetrievalHit(
        chunk_id="c1",
        doc_id=doc_id,
        kb_id="kb1",
        parent_id="p1",
        content=content,
        seq=1,
        heading_path=heading,
        doc_type="markdown",
        page=None,
        score=score,
        parent_content=parent,
    )


def _state(**overrides) -> AgentState:
    base: AgentState = {"query": "退款多久到账", "tenant_id": "t1"}
    base.update(overrides)
    return base


class TestGraphHappyPath:
    async def test_retrieval_result_flows_into_prompt(self) -> None:
        gateway = _FakeGateway("一到三个工作日到账")
        retriever = _FakeRetriever([_hit()])
        graph = build_graph(retriever=retriever, gateway=gateway)

        state = await graph.ainvoke(_state())

        assert state["answer"] == "一到三个工作日到账"
        # 检索入参：查询与租户需原样透传（租户强制过滤）
        assert retriever.calls[0]["query"] == "退款多久到账"
        assert retriever.calls[0]["tenant_id"] == "t1"
        # 知识片段（父块优先）与用户问题均进入提示词
        assert "退款政策 > 时效" in state["prompt"]
        assert "父块章节内容" in state["prompt"]
        assert "退款多久到账" in state["prompt"]
        assert len(state["contexts"]) == 1

    async def test_parent_content_preferred_over_child(self) -> None:
        retriever = _FakeRetriever([_hit(content="子块原文", parent="父块章节内容")])
        graph = build_graph(retriever=retriever, gateway=_FakeGateway())

        state = await graph.ainvoke(_state())

        assert "父块章节内容" in state["context_text"]
        assert "子块原文" not in state["context_text"]

    async def test_history_included_in_prompt(self) -> None:
        gateway = _FakeGateway()
        graph = build_graph(retriever=None, gateway=gateway)

        await graph.ainvoke(
            _state(history=[ChatMessage(role="user", content="你好"), ChatMessage(role="assistant", content="您好")])
        )

        prompt = gateway.prompts[0]
        assert "【对话历史】" in prompt
        assert "user：你好" in prompt
        assert "assistant：您好" in prompt

    async def test_history_trimmed_to_limit(self) -> None:
        gateway = _FakeGateway()
        graph = build_graph(retriever=None, gateway=gateway, history_max_messages=2)
        history = [ChatMessage(role="user", content=f"第{i}条") for i in range(5)]

        state = await graph.ainvoke(_state(history=history))

        assert [message.content for message in state["history"]] == ["第3条", "第4条"]
        assert "第0条" not in gateway.prompts[0]


class TestGraphDegradation:
    async def test_retrieval_failure_degrades_and_still_answers(self) -> None:
        """Milvus 不可达等检索异常不得中断对话，应以空上下文继续生成。"""
        gateway = _FakeGateway("兜底回答")
        retriever = _FakeRetriever(error=RuntimeError("Milvus 不可达"))
        graph = build_graph(retriever=retriever, gateway=gateway)

        state = await graph.ainvoke(_state())

        assert state["answer"] == "兜底回答"
        assert state["contexts"] == []
        assert "未检索到相关知识" in state["prompt"]

    async def test_without_retriever_generates_without_context(self) -> None:
        gateway = _FakeGateway()
        graph = build_graph(retriever=None, gateway=gateway)

        state = await graph.ainvoke(_state())

        assert state["answer"] == "固定回答"
        assert state["contexts"] == []


class TestPromptBuilder:
    def test_no_context_placeholder(self) -> None:
        prompt = build_prompt(_state(context_text=""))
        assert "未检索到相关知识" in prompt

    def test_format_context_numbers_sources(self) -> None:
        text = format_context([_hit(heading="A", parent="甲"), _hit(heading="B", parent="乙")])
        assert text.startswith("[1] 来源：A")
        assert "[2] 来源：B" in text


class TestCreateAgent:
    async def test_wires_retriever_from_store(self) -> None:
        """create_agent 由 store 装配检索器（离线：hash 向量 + 关闭精排）。"""
        rows = [
            {
                "chunk_id": "c1", "doc_id": "d1", "kb_id": "kb1", "parent_id": "p1",
                "content": "内容", "seq": 1, "heading_path": "章", "doc_type": "markdown",
                "page": -1, "score": 0.5,
            }
        ]
        config = Settings(embedder_provider="hash", embed_dim=16, rerank_provider="none")
        gateway = _FakeGateway("装配回答")

        graph = create_agent(config, _FakeStore(rows), gateway)
        state = await graph.ainvoke(_state())

        assert state["answer"] == "装配回答"
        assert "父块:p1" in state["context_text"]  # 命中经父块回填
        assert isinstance(graph, object)

    def test_retriever_is_retriever_type(self) -> None:
        """装配出的检索器类型契约（防止后续替换破坏协议）。"""
        from app.rag.retrieve import create_retriever

        config = Settings(embedder_provider="hash", embed_dim=16, rerank_provider="none")
        assert isinstance(create_retriever(_FakeStore([]), config), Retriever)


# ---------- 真实组件接缝：stream_answer 工具分支（M4-4.1） ----------


@dataclass(slots=True)
class _Call:
    id: str
    name: str
    arguments: str


@dataclass(slots=True)
class _ProviderEvent:
    delta: str = ""
    tool_call: _Call | None = None


class _ToolEventGateway:
    """脚本化 provider（实现 ``stream_events`` 真实接口），按轮次产出文本与工具调用。"""

    def __init__(self, rounds: list[list[_ProviderEvent]]) -> None:
        self._rounds = rounds
        self.calls: list[dict[str, Any]] = []

    async def stream_events(self, messages, tools=None, model=None):
        self.calls.append({"messages": [dict(message) for message in messages], "tools": tools})
        round_events = self._rounds[min(len(self.calls) - 1, len(self._rounds) - 1)]
        for event in round_events:
            yield event


class TestStreamAnswerToolBranch:
    """**真实组件接缝**测试：真实 PiiMasker + 真实注册表/执行器，走 stream_answer 工具分支。

    此前单测使用的是「想象中的 API 替身」（``mask_text``、无 ``business_pb2``、假事件），
    单测全绿但真实链路三处炸裂——本测试以生产接线方式（单条持久还原管道）固化正确行为。
    """

    async def _run(self, *, query: str, gateway: _ToolEventGateway, registry, executor):
        return [
            event
            async for event in stream_answer(
                query,
                gateway=gateway,
                retriever=None,
                tool_registry=registry,
                tool_executor=executor,
                tool_context=ToolContext(tenant_id="t-ctx", session_id="s1", message_id="m1"),
            )
        ]

    def _registry(self, handler) -> ToolRegistry:
        return ToolRegistry(
            [
                ToolSpec(
                    name="get_logistics",
                    description="查询物流",
                    parameters={
                        "type": "object",
                        "properties": {"order_no": {"type": "string", "minLength": 6}},
                        "required": ["order_no"],
                        "additionalProperties": False,
                    },
                    handler=handler,
                )
            ]
        )

    async def test_tool_branch_executes_and_restores_pii(self) -> None:
        executed: list[dict[str, Any]] = []

        async def handler(args, ctx):
            executed.append({"args": args, "tenant": ctx.tenant_id})
            return {"物流状态": "运输中", "运单号": "SF1234567890"}

        registry = self._registry(handler)
        executor = ToolExecutor(registry)
        gateway = _ToolEventGateway(
            [
                [_ProviderEvent(tool_call=_Call(id="c1", name="get_logistics", arguments='{"order_no": "LN20240815002"}'))],
                # 第二轮的片段刻意把占位符切在相邻 delta 之间，验证还原缓冲跨片段/跨轮次有效
                [_ProviderEvent(delta="您的手机号 [手机号"), _ProviderEvent(delta="1] 已登记，运单号 SF1234567890")],
            ]
        )

        events = await self._run(
            query="物流到哪了，我的手机号是13800001111", gateway=gateway, registry=registry, executor=executor
        )
        text = "".join(event.delta for event in events if event.kind is StreamKind.DELTA)

        # 工具真实执行，且租户由上下文注入（非模型传入）
        assert executed == [{"args": {"order_no": "LN20240815002"}, "tenant": "t-ctx"}]
        # 进入模型的用户问题已脱敏：手机号变占位符，原文不外泄
        user_message = gateway.calls[0]["messages"][-1]["content"]
        assert "13800001111" not in user_message and "[手机号1]" in user_message
        # 流出片段已完整还原（含跨片段占位符）
        assert "13800001111" in text and "[手机号" not in text
        assert events[-1].kind is StreamKind.DONE

    async def test_tool_branch_skipped_when_registry_empty(self) -> None:
        """无可用工具时回落到无工具路径：只走 ``stream``，不调用 ``stream_events``。"""

        class _TextGateway:
            def __init__(self) -> None:
                self.calls = 0

            async def generate(self, prompt, model=None):
                return "纯文本回答"

            def stream(self, prompt, model=None):
                self.calls += 1

                async def _gen():
                    yield "纯文本回答"

                return _gen()

        gateway = _TextGateway()
        registry = ToolRegistry([])  # 空注册表：names() 为空，工具分支不启用

        events = [
            event
            async for event in stream_answer(
                "你好",
                gateway=gateway,
                retriever=None,
                tool_registry=registry,
                tool_executor=ToolExecutor(registry),
                tool_context=ToolContext(tenant_id="t1"),
            )
        ]
        text = "".join(event.delta for event in events if event.kind is StreamKind.DELTA)

        assert text == "纯文本回答"
        assert gateway.calls == 1, "无工具时应走纯文本 stream 路径"


class TestStreamAnswerMemory:
    """跨会话长期记忆注入（M4-4.4）：覆盖非工具与工具两条提示词路径。"""

    class _TextGateway:
        def __init__(self) -> None:
            self.prompts: list[str] = []

        def stream(self, prompt, model=None):
            self.prompts.append(prompt)

            async def _gen():
                yield "回答"

            return _gen()

    async def _collect(self, **kwargs) -> list:
        return [event async for event in stream_answer("问题", gateway=kwargs.pop("gateway"), retriever=None, **kwargs)]

    async def test_memory_is_injected_into_prompt(self) -> None:
        gateway = self._TextGateway()
        events = await self._collect(gateway=gateway, memory="偏好：货到付款；身份：企业采购")

        assert any(event.kind is StreamKind.DELTA for event in events)
        assert "【用户长期记忆】" in gateway.prompts[0]
        assert "偏好：货到付款" in gateway.prompts[0]

    async def test_memory_absent_when_empty(self) -> None:
        gateway = self._TextGateway()
        await self._collect(gateway=gateway, memory="")

        assert "【用户长期记忆】" not in gateway.prompts[0]

    async def test_tool_branch_carries_memory(self) -> None:
        """工具分支的提示词（system 上下文消息）同样携带长期记忆。"""

        async def handler(args, ctx):
            return {"ok": True}

        registry = ToolRegistry(
            [
                ToolSpec(
                    name="get_order",
                    description="查询订单",
                    parameters={
                        "type": "object",
                        "properties": {"order_no": {"type": "string", "minLength": 6}},
                        "required": ["order_no"],
                        "additionalProperties": False,
                    },
                    handler=handler,
                )
            ]
        )
        gateway = _ToolEventGateway([[_ProviderEvent(delta="已为您查询")]])

        events = [
            event
            async for event in stream_answer(
                "问题",
                gateway=gateway,
                retriever=None,
                memory="偏好：货到付款",
                tool_registry=registry,
                tool_executor=ToolExecutor(registry),
                tool_context=ToolContext(tenant_id="t-ctx"),
            )
        ]

        assert any(event.kind is StreamKind.DELTA for event in events)
        context_message = gateway.calls[0]["messages"][1]
        assert context_message["role"] == "system"
        assert "【用户长期记忆】" in context_message["content"]
        assert "偏好：货到付款" in context_message["content"]
