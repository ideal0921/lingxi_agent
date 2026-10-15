"""工具框架（M4-4.1 ~ 4.3）：规格、白名单注册表、参数强校验、执行器、审计与业务工具。"""

from .audit import ToolAuditor, ToolAuditRecord, build_record
from .business import (
    BusinessToolClient,
    BusinessToolError,
    build_business_tools,
    make_tool_handlers,
)
from .executor import ToolExecutor
from .registry import ToolRegistry
from .schema import SchemaValidationError, validate_arguments
from .spec import ToolContext, ToolInvocation, ToolOutcome, ToolSpec

__all__ = [
    "BusinessToolClient",
    "BusinessToolError",
    "SchemaValidationError",
    "ToolAuditRecord",
    "ToolAuditor",
    "ToolContext",
    "ToolExecutor",
    "ToolInvocation",
    "ToolOutcome",
    "ToolRegistry",
    "ToolSpec",
    "build_business_tools",
    "build_record",
    "make_tool_handlers",
    "validate_arguments",
]
