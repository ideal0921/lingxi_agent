"""Agent 运行时最小图（M3-3.7）+ 流式生成（M3-3.8）。

非流式图结构（LangGraph ``StateGraph``）::

    START ──▶ load_context ──▶ retrieve ──▶ generate ──▶ END

流式路径（:func:`stream_answer`）复用同一套「上下文裁剪 → 检索 → 提示词拼装」逻辑，
仅把「生成」换成逐片段产出，供 Kafka chunk 链路消费（Python → Kafka → Go → WSS → 前端）。

设计要点：
- **依赖注入**：``retriever`` 与 ``gateway`` 由调用方传入，节点内不含 Milvus / LLM
  具体实现，便于离线单测与替换（生产由 ``create_agent`` 装配）。
- **降级不阻断**：检索失败（Milvus 不可达、超时等）记录告警并以**空上下文**继续生成；
  生成失败由 :class:`LLMGateway` 自身的降级链与指数退避兜底。任一节点失败都不使
  其它会话受影响（每轮状态独立）。
- **不阻塞事件循环**：检索链路为同步阻塞调用（云端嵌入 HTTP + Milvus 同步客户端），
  统一经 ``asyncio.to_thread`` 放入工作线程执行；否则多会话并发时检索会串行排队，
  并拖慢其他会话正在进行的流式输出。
- **PII 脱敏（M3-3.9）**：用户问题、对话历史与检索到的知识片段在**进入模型前**统一
  替换为占位符（云端 embedding 与 LLM 均不外泄原文），回答/流出片段再还原为原文；
  同一轮对话共用同一脱敏器，占位符跨字段一致。
- **最小图边界**：工具调用（Function Calling）与人工接管判定为 M4 增量，长程记忆
  摘要为 M3-3.11；本图不含分支，先打通「检索 + 生成」主路径。

约束：LLM 调用统一走 ``gateway``，禁止绕过（架构关键约束）。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator, Sequence
from typing import TYPE_CHECKING, Any, Protocol

from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.state import AgentState, ChatMessage, StreamEvent, StreamKind
from app.config import settings
from app.privacy import PiiMasker

if TYPE_CHECKING:  # 仅类型标注：保持本模块可离线导入（不拉起 Milvus / LLM 具体实现）
    from app.rag.retrieve import RetrievalHit, Retriever

logger = logging.getLogger(__name__)


class LLMGenerator(Protocol):
    """LLM 生成协议：与 :class:`app.gateway.router.LLMGateway` 兼容的调用形态。"""

    async def generate(self, prompt: str, model: str | None = None) -> str: ...

    def stream(self, prompt: str, model: str | None = None) -> AsyncIterator[str]: ...


_SYSTEM_PROMPT = (
    "你是灵犀智能客服助手。请**仅依据「知识片段」**回答用户问题，"
    "若知识片段不足以回答，请如实说明并建议转人工，禁止编造。"
)


def format_context(hits: Sequence[RetrievalHit]) -> str:
    """把检索命中拼装为带来源标注的知识片段。

    优先使用检索回填的父块内容（章节级上下文更完整），缺失时退回子块原文。
    """
    blocks: list[str] = []
    for index, hit in enumerate(hits, start=1):
        content = hit.parent_content or hit.content
        source = hit.heading_path or hit.doc_id
        blocks.append(f"[{index}] 来源：{source}\n{content}")
    return "\n\n".join(blocks)


def build_prompt(state: AgentState) -> str:
    """按「系统提示 + 会话摘要 + 知识片段 + 对话历史 + 用户问题」拼装提示词。

    未检索到知识时显式写入占位说明，引导模型如实拒答而非编造。
    """
    parts: list[str] = [_SYSTEM_PROMPT]
    # 上下文窗口（M3-3.11）：超出窗口的旧对话已压缩为摘要，先于近期历史注入
    summary = (state.get("summary") or "").strip()
    if summary:
        parts.append("【会话摘要】\n" + summary)
    context_text = state.get("context_text") or ""
    parts.append(
        "【知识片段】\n" + (context_text if context_text else "（本次未检索到相关知识）")
    )

    history = state.get("history") or []
    if history:
        lines = [f"{message.role}：{message.content}" for message in history]
        parts.append("【对话历史】\n" + "\n".join(lines))

    parts.append(f"【用户问题】\n{state.get('query', '')}")
    parts.append("请给出简洁准确的中文回答。")
    return "\n\n".join(parts)


def _trim_history(history: Sequence[ChatMessage], limit: int) -> list[ChatMessage]:
    """按上限裁剪近期对话，避免提示词无限膨胀（limit<=0 表示不裁剪）。"""
    items = list(history)
    return items[-limit:] if limit > 0 else items


def _new_masker() -> PiiMasker:
    """按配置创建会话级脱敏器（一次对话/一次流转共用一个）。"""
    return PiiMasker(enabled=settings.pii_enabled, mask_address=settings.pii_mask_address)


def _mask_history(masker: PiiMasker, history: Sequence[ChatMessage]) -> list[ChatMessage]:
    """脱敏历史消息（新建对象，避免就地修改调用方持有的历史）。"""
    return [ChatMessage(role=message.role, content=masker.mask(message.content)) for message in history]


def _retrieve_contexts(
    retriever: Retriever | None, query: str, tenant_id: str, kb_id: str | None
) -> tuple[list[RetrievalHit], str]:
    """执行混合检索并返回（命中列表, 知识片段文本）；失败降级为无上下文。"""
    if retriever is None:
        return [], ""
    try:
        hits = retriever.retrieve(query, tenant_id=tenant_id, kb_id=kb_id)
    except Exception as exc:  # noqa: BLE001 - 检索失败必须降级而非中断对话
        logger.warning("知识检索失败，降级为无上下文生成: %s", exc)
        return [], ""
    return hits, format_context(hits)


def build_graph(
    *,
    retriever: Retriever | None = None,
    gateway: LLMGenerator,
    history_max_messages: int | None = None,
) -> CompiledStateGraph:
    """构建并编译 Agent 最小图（非流式）。

    Args:
        retriever: 知识检索器；``None`` 表示不检索（无知识库场景仍可生成）。
        gateway: LLM 网关（必须实现 :class:`LLMGenerator`）。
        history_max_messages: 上下文保留的最大消息数；缺省取配置项。

    Returns:
        已编译图，调用 ``await graph.ainvoke(state)`` 执行单轮对话。
    """
    limit = (
        settings.agent_history_max_messages
        if history_max_messages is None
        else history_max_messages
    )

    async def load_context(state: AgentState) -> dict[str, Any]:
        """加载上下文：取近期对话并按上限裁剪，同时对历史与本轮问题做 PII 脱敏。"""
        masker = _new_masker()
        trimmed = _trim_history(state.get("history") or [], limit)
        return {
            "history": _mask_history(masker, trimmed),
            "query_masked": masker.mask(state.get("query", "")),
            "pii_masker": masker,
        }

    async def retrieve(state: AgentState) -> dict[str, Any]:
        """混合检索 + 精排 + 父块回填；失败降级为空上下文（不阻断生成）。

        检索用**脱敏后的问题**（云端嵌入同样不应接触 PII），命中的知识片段在送入
        LLM 前再统一脱敏。检索为同步阻塞调用，故放入工作线程执行，避免阻塞事件循环。
        """
        masker = state.get("pii_masker")
        query = state.get("query_masked") or state.get("query", "")
        hits, context_text = await asyncio.to_thread(
            _retrieve_contexts, retriever, query, state.get("tenant_id", ""), state.get("kb_id")
        )
        if masker is not None and context_text:
            context_text = masker.mask(context_text)
        return {"contexts": hits, "context_text": context_text}

    async def generate(state: AgentState) -> dict[str, Any]:
        """拼装提示词并调用 LLM 网关生成；输入已脱敏，输出还原为原文。"""
        masker = state.get("pii_masker")
        prompt = build_prompt(
            {
                "query": state.get("query_masked") or state.get("query", ""),
                "history": state.get("history"),
                "context_text": state.get("context_text"),
                "summary": state.get("summary", ""),
            }
        )
        answer = await gateway.generate(prompt)
        if masker is not None:
            answer = masker.restore(answer)
        return {"prompt": prompt, "answer": answer}

    builder = StateGraph(AgentState)
    builder.add_node("load_context", load_context)
    builder.add_node("retrieve", retrieve)
    builder.add_node("generate", generate)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "retrieve")
    builder.add_edge("retrieve", "generate")
    builder.add_edge("generate", END)
    return builder.compile()


async def stream_answer(
    query: str,
    *,
    gateway: LLMGenerator,
    tenant_id: str = "",
    kb_id: str | None = None,
    history: Sequence[ChatMessage] | None = None,
    summary: str = "",
    retriever: Retriever | None = None,
    history_max_messages: int | None = None,
) -> AsyncIterator[StreamEvent]:
    """流式生成一轮回答（M3-3.8）：先产出上下文事件，再逐片段产出增量。

    复用非流式图的「历史裁剪 → 检索 → 提示词拼装」逻辑，保证两条路径**提示词与
    上下文完全一致**（避免流式/非流式答案漂移）；检索失败同样降级为空上下文。
    查询、历史与知识片段在进模型前脱敏，流出片段在产出后**按占位符边界缓冲还原**。

    Yields:
        :class:`StreamEvent`：``CONTEXT``（含引用）→ 若干 ``DELTA`` → ``DONE``。
    """
    limit = (
        settings.agent_history_max_messages
        if history_max_messages is None
        else history_max_messages
    )
    masker = _new_masker()
    masked_query = masker.mask(query)
    trimmed = _mask_history(masker, _trim_history(list(history or []), limit))
    # 检索为同步阻塞调用（云端嵌入 HTTP + Milvus），放工作线程执行，避免阻塞事件循环
    hits, context_text = await asyncio.to_thread(
        _retrieve_contexts, retriever, masked_query, tenant_id, kb_id
    )
    if context_text:
        context_text = masker.mask(context_text)
    yield StreamEvent(kind=StreamKind.CONTEXT, contexts=hits)

    prompt = build_prompt(
        {"query": masked_query, "history": trimmed, "context_text": context_text, "summary": summary}
    )
    async for delta in masker.restore_stream(gateway.stream(prompt)):
        yield StreamEvent(kind=StreamKind.DELTA, delta=delta)
    yield StreamEvent(kind=StreamKind.DONE)


def create_agent(
    config: Any,
    store: Any,
    gateway: LLMGenerator,
    *,
    history_max_messages: int | None = None,
) -> CompiledStateGraph:
    """生产装配：由文档存储构建检索器，再连同 LLM 网关构建并编译图。

    Args:
        config: 应用配置（透传给检索器装配：embedder / rerank / 召回参数）。
        store: 检索存储（生产为 :class:`app.rag.index.writer.ChunkStore`）。
        gateway: LLM 网关。
        history_max_messages: 上下文保留的最大消息数。

    Returns:
        已编译的 Agent 图（惰性装配，不在此处建立 Milvus / 网络连接）。
    """
    from app.rag.retrieve import create_retriever

    return build_graph(
        retriever=create_retriever(store, config),
        gateway=gateway,
        history_max_messages=history_max_messages,
    )
