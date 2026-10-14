"""Agent 最小图单测（M3-3.7，离线运行，不连 Milvus / 不触网）。

覆盖：节点编排（加载上下文 → 检索 → 生成）、提示词拼装（知识片段/历史/问题）、
上下文裁剪、父块优先、检索失败降级不阻断、无检索器场景，以及 create_agent 装配。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence

from app.agent.graph import build_graph, build_prompt, create_agent, format_context
from app.agent.state import AgentState, ChatMessage
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
