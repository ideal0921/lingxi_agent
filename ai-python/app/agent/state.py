"""Agent 运行时对话状态（M3-3.7）。

LangGraph 以 ``TypedDict`` 作为状态模式：节点返回的字典会**按键合并覆盖**进状态。
本状态覆盖「加载上下文 → 检索 → 生成」最小图所需的全部字段；工具调用结果、
人工接管标记、长程记忆摘要等随后续任务（M3-3.9/3.11）增量扩展。

标注说明：``contexts`` 直接复用 RAG 检索命中类型（:class:`RetrievalHit`），保持
「检索 → 生成」全链路同构，避免重复定义数据结构。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, TypedDict

from app.rag.retrieve import RetrievalHit


class StreamKind(StrEnum):
    """流式事件类型。"""

    CONTEXT = "context"  # 检索完成：携带命中列表（供引用标注）
    DELTA = "delta"  # 增量片段
    DONE = "done"  # 本轮流结束


@dataclass(slots=True)
class StreamEvent:
    """Agent 流式事件：把「上下文 → 增量片段 → 结束」统一为一类可消费事件。

    Attributes:
        kind: 事件类型（见 :class:`StreamKind`）。
        delta: ``DELTA`` 事件的内容片段，其余类型为空。
        contexts: ``CONTEXT`` 事件的检索命中列表。
    """

    kind: StreamKind
    delta: str = ""
    contexts: list[RetrievalHit] = field(default_factory=list)


@dataclass(slots=True)
class ChatMessage:
    """对话消息：供上下文拼装（近期 N 轮）。

    Attributes:
        role: 角色，``user`` / ``assistant``（``system`` 由提示词承担，不入历史）。
        content: 消息正文。
    """

    role: str
    content: str


class AgentState(TypedDict, total=False):
    """Agent 单轮对话状态。

    全部字段可选（``total=False``）：入口只填 query / tenant_id 等入参，节点逐步
    补全 history / contexts / context_text / prompt / answer；未流经的字段不存在。
    """

    # ---- 入参 ----
    query: str  # 用户本轮问题（原始文本）
    tenant_id: str  # 租户标识（知识隔离，检索强制携带）
    kb_id: str | None  # 知识库标识（可选二级过滤）
    session_id: str  # 会话标识（供记忆与审计关联）
    history: list[ChatMessage]  # 近期对话（按时间升序；进模型前已被脱敏）
    memory: str  # 跨会话用户长期记忆（用户级画像，M4-4.4；按 tenant+user 隔离）

    # ---- 中间产物 ----
    query_masked: str  # 脱敏后的用户问题（检索与提示词使用，避免 PII 进入模型）
    pii_masker: Any  # 会话级脱敏器（承载占位符↔原文映射，供回答还原）
    summary: str  # 会话长程摘要（超出窗口的旧消息压缩结果，M3-3.11）
    contexts: list[RetrievalHit]  # 检索命中（供引用标注）
    context_text: str  # 拼装后的知识片段文本（已脱敏）
    prompt: str  # 最终送入 LLM 的提示词（便于审计与评测回放）
    tool_context: Any  # 工具执行上下文（M4-4.8：编排路径按请求注入，勿进编译期闭包）

    # ---- 编排观测（M4-4.8：覆盖语义，仅用于链路追踪与日志） ----
    workflow_id: str  # 本轮执行的工作流标识（未走编排时为空）
    workflow_version: int  # 本轮加载的工作流版本号（未走编排时为 0）

    # ---- 出参 ----
    answer: str  # 模型回答
