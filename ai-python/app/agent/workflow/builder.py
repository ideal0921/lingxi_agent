"""图定义 → LangGraph 图编译（M4-4.8）。

把 Studio 产出的 JSON 图（``llm``/``retrieve``/``tool``/``branch`` 四类节点）编译为
可执行的 ``StateGraph``：

- ``retrieve``：混合检索（可覆盖 ``kb_id``/``top_k``）→ 写 ``contexts``/``context_text``，
  并以自定义流事件下发 ``CONTEXT``（供上层沿用转人工判定与引用标注）；
- ``tool``：**编译期**把 ``tool_name`` 汇总为工具白名单（节点本身透传）；
- ``branch``：透传节点 + ``add_conditional_edges``（路由函数求值白名单表达式；
  求值失败走 ``false`` 出边并告警——``false`` 为约定兜底路径）；
- ``llm``：终端流式节点，逐片段经 ``get_stream_writer()`` 写出，返回 ``answer``/``prompt``。

与既有内置图的**不变式保持一致**（避免两条路径行为漂移）：

- 节点内只产出**原始文本**片段，**不做 PII 还原**——还原由调用方用**单条**持久
  ``PiiMasker.restore_stream`` 管道包住整条流（占位符跨片段/跨工具轮次不被切断）；
- 检索等同步阻塞调用统一经 ``asyncio.to_thread`` 放入工作线程；
- 图内异常不外抛断整轮：检索失败降级为空上下文，LLM 失败由网关降级链兜底，
  工具失败由执行器重试/降级。

**编译期与请求期分离**：已编译图按 ``(workflow_id, version)`` 缓存复用，故闭包内只允许
存放编译期常量（节点配置、工具白名单、作用域注册表）；一切**每请求**的东西
（脱敏器、工具上下文）经 ``AgentState`` 传入，不得进闭包。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from typing import TYPE_CHECKING, Any

from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.graph import (
    DEFAULT_SYSTEM_PROMPT,
    build_prompt,
    build_tool_context,
    format_context,
)
from app.agent.state import AgentState, StreamEvent, StreamKind
from app.agent.tool_loop import build_tool_messages, stream_tool_answer
from app.agent.workflow.expression import build_variables, evaluate
from app.agent.workflow.models import (
    EdgeCondition,
    NodeType,
    WorkflowDefinition,
    WorkflowDefinitionError,
    WorkflowNode,
)

if TYPE_CHECKING:  # 仅类型标注：保持本模块可离线导入（不拉起 Milvus / LLM / gRPC）
    from app.agent.graph import LLMGenerator
    from app.agent.tools.executor import ToolExecutor
    from app.agent.tools.registry import ToolRegistry
    from app.rag.retrieve import RetrievalHit, Retriever

logger = logging.getLogger(__name__)

# 分支表达式长度上限（缺省值，可由配置覆盖）
DEFAULT_EXPRESSION_MAX_LENGTH = 200


def _noop_writer(_: Any) -> None:
    """非自定义流场景的占位 writer（``ainvoke`` 执行时无 custom 流）。"""


def _safe_writer() -> Callable[[Any], None]:
    """取当前节点的自定义流 writer；不可用时返回空实现（不因执行方式差异而抛错）。"""
    try:
        return get_stream_writer()
    except Exception:  # noqa: BLE001 - 非 custom 流模式（如 ainvoke）下无 writer
        return _noop_writer


def build_workflow_graph(
    definition: WorkflowDefinition,
    *,
    gateway: LLMGenerator,
    retriever: Retriever | None = None,
    tool_registry: ToolRegistry | None = None,
    tool_executor: ToolExecutor | None = None,
    tool_max_rounds: int = 3,
    expression_max_length: int = DEFAULT_EXPRESSION_MAX_LENGTH,
) -> CompiledStateGraph:
    """把图定义编译为 LangGraph 图。

    Args:
        definition: 已解析的图定义（来自已发布版本快照）。
        gateway: LLM 网关（流式与工具链路共用）。
        retriever: 知识检索器；``None`` 表示检索节点降级为空上下文。
        tool_registry / tool_executor: 工具白名单与执行器；``None`` 时 ``tool`` 节点失效
            （LLM 节点按无工具路径生成）。
        tool_max_rounds: Function Calling 轮次上限。
        expression_max_length: 分支表达式长度上限。

    Raises:
        WorkflowDefinitionError: 入口不唯一、分支目标缺失、边指向不存在的节点。
    """
    entry = definition.entry_id()
    if entry is None:
        raise WorkflowDefinitionError("图必须恰好有一个入口节点")

    # 编译期常量：工具面收敛一次，避免每个请求重建注册表（含 schema 校验开销）
    scoped_registry = _scoped_registry(definition, tool_registry)

    builder = StateGraph(AgentState)
    for node in definition.nodes:
        if node.type is NodeType.RETRIEVE:
            builder.add_node(node.id, _make_retrieve_node(node, retriever))
        elif node.type is NodeType.LLM:
            builder.add_node(
                node.id,
                _make_llm_node(
                    node,
                    gateway=gateway,
                    scoped_registry=scoped_registry,
                    tool_executor=tool_executor,
                    tool_max_rounds=tool_max_rounds,
                ),
            )
        else:  # TOOL / BRANCH 均为透传节点
            builder.add_node(node.id, _pass_through)

    builder.add_edge(START, entry)
    _wire_edges(builder, definition, expression_max_length)
    graph = builder.compile()
    logger.info(
        "工作流图已编译 nodes=%d edges=%d tools=%s",
        len(definition.nodes),
        len(definition.edges),
        scoped_registry.names() if scoped_registry is not None else [],
    )
    return graph


def _scoped_registry(
    definition: WorkflowDefinition, registry: ToolRegistry | None
) -> ToolRegistry | None:
    """按图声明的工具面收敛注册表（未声明或未注册的工具一律不可调用）。"""
    declared = definition.allowed_tools()
    if not declared:
        return None
    if registry is None:
        logger.warning("工作流声明了工具但工具注册表未装配，工具面收敛为空：%s", declared)
        return None
    allowed = [name for name in declared if registry.is_allowed(name)]
    if len(allowed) != len(declared):
        logger.warning("工作流声明的部分工具未注册/未启用，已剔除：%s", declared)
    if not allowed:
        return None
    from app.agent.tools.registry import ToolRegistry as Registry

    return Registry(registry.specs(), enabled_names=allowed)


async def _pass_through(_: AgentState) -> dict[str, Any]:
    """透传节点（分支/工具声明）：不改变状态。"""
    return {}


def _make_retrieve_node(node: WorkflowNode, retriever: Retriever | None) -> Callable[[AgentState], Any]:
    """构造检索节点：可覆盖 ``kb_id``/``top_k``，失败降级为空上下文。"""
    kb_id = node.config_str("kb_id")
    top_k = node.config_int("top_k", 0) or None

    async def retrieve_node(state: AgentState) -> dict[str, Any]:
        masker = state.get("pii_masker")
        query = state.get("query_masked") or state.get("query", "")
        # 检索为同步阻塞（云端嵌入 + Milvus），放工作线程执行，避免阻塞事件循环
        hits, context_text = await asyncio.to_thread(
            _retrieve_with_overrides, retriever, query, state.get("tenant_id", ""), kb_id, top_k
        )
        if masker is not None and context_text:
            context_text = masker.mask(context_text)
        # 下发 CONTEXT 事件：上层据此做转人工判定与引用标注
        _safe_writer()(StreamEvent(kind=StreamKind.CONTEXT, contexts=hits))
        return {"contexts": hits, "context_text": context_text}

    return retrieve_node


def _retrieve_with_overrides(
    retriever: Retriever | None,
    query: str,
    tenant_id: str,
    kb_id: str | None,
    top_k: int | None,
) -> tuple[list[RetrievalHit], str]:
    """按节点配置执行检索（覆盖 kb_id/top_k）；空查询或无检索器时返回空上下文。"""
    if retriever is None or not query.strip():
        return [], ""
    try:
        hits = retriever.retrieve(query, tenant_id=tenant_id, kb_id=kb_id, top_k=top_k)
    except Exception as exc:  # noqa: BLE001 - 检索失败必须降级而非中断对话
        logger.warning("工作流检索节点失败，降级为无上下文: %s", exc)
        return [], ""
    return hits, format_context(hits)


def _make_router(node: WorkflowNode, *, expression_max_length: int) -> Callable[[AgentState], str]:
    """构造分支路由函数：求值表达式并返回出边 handle（``true``/``false``）。"""
    expression = node.config_str("expression")

    def router(state: AgentState) -> str:
        contexts = state.get("contexts") or []
        scores = [float(getattr(hit, "score", 0.0) or 0.0) for hit in contexts]
        query = state.get("query_masked") or state.get("query", "")
        variables = build_variables(scores=scores, has_hits=bool(contexts), query_length=len(query))
        result = evaluate(expression or "", variables, max_length=expression_max_length)
        if result is None:
            # 求值失败按约定走 false 出边（兜底路径），并记录告警
            logger.warning(
                "分支节点 %s 表达式求值失败，走 false 兜底分支：%.60s", node.id, expression or ""
            )
            return "false"
        return "true" if result else "false"

    return router


def _make_llm_node(
    node: WorkflowNode,
    *,
    gateway: LLMGenerator,
    scoped_registry: ToolRegistry | None,
    tool_executor: ToolExecutor | None,
    tool_max_rounds: int,
) -> Callable[[AgentState], Any]:
    """构造 LLM 节点：按需走「工具 + 流式」或「纯提示词 + 流式」，逐片段写出原始文本。"""
    model = node.config_str("model")
    system_prompt = node.config_str("system_prompt") or DEFAULT_SYSTEM_PROMPT
    use_tools = scoped_registry is not None and tool_executor is not None and bool(scoped_registry.names())

    async def llm_node(state: AgentState) -> dict[str, Any]:
        writer = _safe_writer()
        masker = state.get("pii_masker")
        query = state.get("query_masked") or state.get("query", "")
        history = state.get("history") or []
        context_text = state.get("context_text") or ""
        summary = state.get("summary", "")
        memory = state.get("memory", "")
        # 长期记忆进模型前脱敏（历史脏数据兜底；本轮 token_map 可正常还原）
        memory_masked = masker.mask(memory) if (masker is not None and memory) else memory

        if use_tools:
            messages = build_tool_messages(
                system_prompt=system_prompt,
                knowledge_text=context_text,
                summary=summary,
                memory=memory_masked,
                history=history,
                query=query,
            )
            tool_ctx = build_tool_context(
                state.get("tool_context"), tenant_id=state.get("tenant_id", ""), masker=masker
            )
            raw_stream = stream_tool_answer(
                gateway=gateway,
                messages=messages,
                registry=scoped_registry,
                executor=tool_executor,
                ctx=tool_ctx,
                max_rounds=tool_max_rounds,
            )
            prompt = _messages_prompt(messages)
        else:
            prompt = build_prompt(
                {
                    "query": query,
                    "history": history,
                    "context_text": context_text,
                    "summary": summary,
                    "memory": memory_masked,
                },
                system_prompt=system_prompt,
            )
            raw_stream = gateway.stream(prompt, model)

        collected: list[str] = []
        async for delta in raw_stream:
            if not delta:
                continue
            collected.append(delta)
            writer(StreamEvent(kind=StreamKind.DELTA, delta=delta))
        return {"prompt": prompt, "answer": "".join(collected)}

    return llm_node


def _messages_prompt(messages: list[dict[str, Any]]) -> str:
    """把消息序列压成可审计的提示词文本（与内置图 ``prompt`` 字段口径一致）。"""
    return "\n\n".join(
        f"{item.get('role', '')}：{item.get('content', '')}" for item in messages if item.get("content")
    )


def _wire_edges(builder: Any, definition: WorkflowDefinition, expression_max_length: int) -> None:
    """按图定义连线：分支节点走条件边，其余走普通边；无出边的节点接 END。"""
    node_ids = {node.id for node in definition.nodes}
    for node in definition.nodes:
        outs = definition.outgoing(node.id)
        if not outs:
            builder.add_edge(node.id, END)
            continue
        if node.type is NodeType.BRANCH:
            targets = definition.branch_targets(node.id)
            if set(targets) != {"true", "false"}:
                raise WorkflowDefinitionError(f"分支节点 {node.id} 缺少 true/false 出边")
            for handle, target in targets.items():
                if target not in node_ids:
                    raise WorkflowDefinitionError(
                        f"分支节点 {node.id} 的 {handle} 出边指向不存在的节点：{target}"
                    )
            builder.add_conditional_edges(
                node.id, _make_router(node, expression_max_length=expression_max_length), targets
            )
            continue
        for edge in outs:
            if edge.condition is not EdgeCondition.ALWAYS:
                raise WorkflowDefinitionError(f"节点 {node.id} 的普通出边必须为 always")
            builder.add_edge(node.id, edge.target)
