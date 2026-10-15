"""工具白名单注册表（M4-4.1）。

- **注册表即白名单**：只有 ``register`` 过的工具才可能在提示词中暴露给模型、才可能被执行；
  ``get`` 返回 ``None`` 时执行器一律拒绝（含未注册/被禁用两种情况）；
- 支持按名称子集启用（``enabled_names``），便于按租户/场景收敛工具面；
- ``openai_tools()`` 生成传给 LLM 的 tools 定义，与执行侧共用同一份 schema（单一事实来源）。
"""

from __future__ import annotations

import logging
from collections.abc import Iterable

from app.agent.tools.schema import build_validator
from app.agent.tools.spec import ToolSpec

logger = logging.getLogger(__name__)


class ToolRegistry:
    """工具注册表（白名单）。"""

    def __init__(self, specs: Iterable[ToolSpec] | None = None, enabled_names: Iterable[str] | None = None) -> None:
        self._specs: dict[str, ToolSpec] = {}
        self._enabled: set[str] | None = set(enabled_names) if enabled_names is not None else None
        for spec in specs or []:
            self.register(spec)

    def register(self, spec: ToolSpec) -> None:
        """注册工具（重名直接报错，避免静默覆盖导致行为不可预期）。"""
        if spec.name in self._specs:
            raise ValueError(f"工具重名：{spec.name}")
        if spec.parameters.get("type") != "object":
            raise ValueError(f"工具 {spec.name} 的 schema 必须是 object")
        if spec.parameters.get("additionalProperties") is not False:
            raise ValueError(f"工具 {spec.name} 的 schema 必须声明 additionalProperties=false（字段白名单）")
        build_validator(spec.parameters)  # 提前校验 schema 合法性，启动即暴露问题
        self._specs[spec.name] = spec
        logger.info("工具已注册：%s", spec.name)

    def get(self, name: str) -> ToolSpec | None:
        """按名称取工具；未注册或未启用的返回 ``None``（调用侧据此拒绝）。"""
        if self._enabled is not None and name not in self._enabled:
            return None
        return self._specs.get(name)

    def is_allowed(self, name: str) -> bool:
        return self.get(name) is not None

    def specs(self) -> list[ToolSpec]:
        """当前启用且已注册的工具（按注册顺序）。"""
        return [spec for name, spec in self._specs.items() if self.is_allowed(name)]

    def names(self) -> list[str]:
        return [spec.name for spec in self.specs()]

    def openai_tools(self) -> list[dict]:
        """传给 LLM 的 tools 定义；无可用工具时返回空列表（调用侧应跳过工具分支）。"""
        return [spec.to_openai_tool() for spec in self.specs()]
