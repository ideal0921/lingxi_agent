"""工具规格与调用记录（M4-4.1 工具框架）。

设计要点：
- 工具 = **名称 + JSON Schema 参数 + 执行器 + 超时/重试/降级策略**，全部显式声明，
  便于「白名单控制 + 参数强校验 + 统一审计」；
- 执行器签名统一为 ``async (args, ctx) -> 结果``，``ctx`` 携带租户/会话等**服务端注入**
  的身份信息，禁止模型自行传入（防越权）；
- 结果要求可 JSON 序列化，最终会被转成文本回填给模型。
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class ToolContext:
    """工具执行上下文：身份与链路信息由服务端注入（模型无法指定）。"""

    tenant_id: str = ""
    session_id: str = ""
    user_id: str = ""
    message_id: str = ""
    masker: Any = None  # 可选的 PII 脱敏器（审计入参/结果时使用）


ToolHandler = Callable[[dict[str, Any], ToolContext], Awaitable[Any]]


@dataclass(slots=True, frozen=True)
class ToolSpec:
    """一个可被模型调用的工具。"""

    name: str
    description: str
    parameters: dict[str, Any]  # JSON Schema（type=object，且必须 additionalProperties=false）
    handler: ToolHandler
    timeout_seconds: float = 5.0  # 单次调用超时（架构约束：工具调用 < 5s）
    max_retries: int = 1  # 失败重试次数（不含首次）
    degrade_message: str = ""  # 失败降级话术（回填给模型，避免答非所问）

    def to_openai_tool(self) -> dict[str, Any]:
        """转成 OpenAI 兼容的 tools 定义。"""
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


@dataclass(slots=True)
class ToolInvocation:
    """模型请求的一次工具调用（arguments 为未解析的 JSON 字符串）。"""

    id: str
    name: str
    arguments: str = "{}"

    def to_openai_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "type": "function",
            "function": {"name": self.name, "arguments": self.arguments},
        }


@dataclass(slots=True)
class ToolOutcome:
    """一次工具调用的执行结果（回填给模型 + 供审计）。"""

    invocation_id: str
    name: str
    ok: bool
    content: str  # 回填模型的内容（成功为结果文本；失败为可读原因/降级话术）
    error: str = ""
    degraded: bool = False
    attempts: int = 1
    duration_ms: float = 0.0
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def rejected(self) -> bool:
        """是否在「调用前」被拒（未注册 / 参数非法），此类调用不产生业务副作用。"""
        return not self.ok and self.attempts == 0
