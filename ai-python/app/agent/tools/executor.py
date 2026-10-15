"""工具执行器（M4-4.1 / 4.2 / 4.3）：白名单 → 参数强校验 → 超时与重试 → 降级 → 审计。

执行顺序与拒绝语义：

1. **白名单**：未注册/未启用的工具名 → 直接拒绝（``attempts=0``，无任何业务副作用）；
2. **参数强校验**：JSON Schema 不通过 → 拒绝，并把可读原因回填给模型（便于自我纠正）；
3. **执行**：超时 ``timeout_seconds``（默认 5s，满足架构约束）内完成；失败重试 ``max_retries`` 次；
4. **降级**：仍失败 → 返回降级话术（说明无法查询并建议核实/转人工），并把异常摘要回填模型；
5. **审计**：以上每种结果都记录（含被拒与降级）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

from app.agent.tools.audit import ToolAuditor, build_record
from app.agent.tools.registry import ToolRegistry
from app.agent.tools.schema import SchemaValidationError, validate_arguments
from app.agent.tools.spec import ToolContext, ToolInvocation, ToolOutcome

logger = logging.getLogger(__name__)

DEFAULT_DEGRADE_MESSAGE = "该功能暂时不可用，请稍后重试或转人工核实。"
MAX_RESULT_CHARS = 4000


class ToolExecutor:
    """按注册表执行工具调用，并产出可回填模型的结果。"""

    def __init__(
        self,
        registry: ToolRegistry,
        *,
        auditor: ToolAuditor | None = None,
        default_timeout_seconds: float = 5.0,
        default_max_retries: int = 1,
        degrade_message: str = "",
        result_max_chars: int = MAX_RESULT_CHARS,
    ) -> None:
        self._registry = registry
        self._auditor = auditor
        self._default_timeout = default_timeout_seconds
        self._default_retries = default_max_retries
        self._degrade_message = degrade_message or DEFAULT_DEGRADE_MESSAGE
        self._result_max_chars = result_max_chars

    @property
    def registry(self) -> ToolRegistry:
        return self._registry

    async def execute(self, invocation: ToolInvocation, ctx: ToolContext) -> ToolOutcome:
        """执行一次工具调用（任何结果都会审计，且不会向上抛异常）。"""
        started = time.perf_counter()

        spec = self._registry.get(invocation.name)
        if spec is None:
            return await self._reject(
                invocation, ctx, started, f"工具「{invocation.name}」未注册或未启用，已拒绝调用", "unregistered"
            )

        try:
            arguments = validate_arguments(spec.parameters, invocation.arguments)
        except SchemaValidationError as exc:
            return await self._reject(invocation, ctx, started, f"参数不合法：{exc}", "invalid_arguments")

        timeout = spec.timeout_seconds or self._default_timeout
        retries = spec.max_retries if spec.max_retries is not None else self._default_retries
        attempts = 0
        last_error = ""

        for attempt in range(retries + 1):
            attempts += 1
            try:
                raw = await asyncio.wait_for(spec.handler(arguments, ctx), timeout=timeout)
                content = self._render(raw)
                duration_ms = (time.perf_counter() - started) * 1000
                outcome = ToolOutcome(
                    invocation_id=invocation.id,
                    name=invocation.name,
                    ok=True,
                    content=content,
                    attempts=attempts,
                    duration_ms=duration_ms,
                )
                await self._audit(invocation, ctx, outcome, arguments)
                return outcome
            except TimeoutError:
                last_error = f"调用超时（>{timeout:g}s）"
            except Exception as exc:  # noqa: BLE001 - 工具实现异常统一转为降级
                last_error = f"{type(exc).__name__}: {exc}"
            if attempt < retries:
                logger.warning("工具调用失败，准备重试 tool=%s attempt=%d 原因=%s",
                               invocation.name, attempt + 1, last_error)

        duration_ms = (time.perf_counter() - started) * 1000
        degrade_message = spec.degrade_message or self._degrade_message
        content = f"{degrade_message}（失败原因：{last_error}）"
        outcome = ToolOutcome(
            invocation_id=invocation.id,
            name=invocation.name,
            ok=False,
            content=content,
            error=last_error,
            degraded=True,
            attempts=attempts,
            duration_ms=duration_ms,
        )
        await self._audit(invocation, ctx, outcome, arguments)
        return outcome

    async def _reject(
        self, invocation: ToolInvocation, ctx: ToolContext, started: float, content: str, error: str
    ) -> ToolOutcome:
        """调用前拒绝（未注册 / 参数非法）：不执行任何业务动作。"""
        outcome = ToolOutcome(
            invocation_id=invocation.id,
            name=invocation.name,
            ok=False,
            content=content,
            error=error,
            attempts=0,
            duration_ms=(time.perf_counter() - started) * 1000,
        )
        await self._audit(invocation, ctx, outcome, {})
        return outcome

    async def _audit(
        self,
        invocation: ToolInvocation,
        ctx: ToolContext,
        outcome: ToolOutcome,
        arguments: dict[str, Any] | str,
    ) -> None:
        if self._auditor is None:
            return
        raw_arguments = arguments if isinstance(arguments, str) else json.dumps(arguments, ensure_ascii=False)
        record = build_record(
            tool=outcome.name,
            invocation_id=outcome.invocation_id,
            session_id=getattr(ctx, "session_id", ""),
            tenant_id=getattr(ctx, "tenant_id", ""),
            message_id=getattr(ctx, "message_id", ""),
            arguments=raw_arguments,
            ok=outcome.ok,
            degraded=outcome.degraded,
            rejected=outcome.rejected,
            attempts=outcome.attempts,
            duration_ms=outcome.duration_ms,
            error=outcome.error,
            result=outcome.content,
            masker=getattr(ctx, "masker", None),
        )
        await self._auditor.record(record)

    def _render(self, raw: Any) -> str:
        """把工具返回值渲染为回填模型的文本（可 JSON 序列化的对象转 JSON）。"""
        text = raw if isinstance(raw, str) else json.dumps(raw, ensure_ascii=False, default=str)
        text = text or ""
        return text if len(text) <= self._result_max_chars else text[: self._result_max_chars] + "…（结果已截断）"
