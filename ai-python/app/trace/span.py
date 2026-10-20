"""Span 数据结构（M5-5.9）：与 MySQL 表 ``lingxi_api_trace`` 逐列对应。

字段契约（三端统一）：
- ``service`` 固定 ``ai-python``；
- ``component`` ∈ ``HTTP`` / ``KAFKA``；
- ``status`` ∈ ``OK`` / ``ERROR``；
- ``started_at`` 为 epoch 毫秒；
- ``entry`` 为 HTTP 路径 / Kafka topic。

**绝不落请求体/响应体**：``error_msg`` 仅放异常摘要并截断到表定义上限 512。
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from uuid import uuid4

# 本服务的固定标识（与 Java/Go 端区分）
SERVICE = "ai-python"

# 表的列顺序：INSERT 占位符顺序必须与本元组严格一致
COLUMNS = (
    "span_id",
    "trace_id",
    "parent_span_id",
    "service",
    "component",
    "entry",
    "http_method",
    "status",
    "status_code",
    "tenant_id",
    "user_id",
    "role",
    "client_ip",
    "elapsed_ms",
    "error_msg",
    "started_at",
)

_ERROR_MSG_MAX = 512


def new_span_id() -> str:
    """生成新的 span_id（32 位 hex）。"""
    return uuid4().hex


def now_ms() -> int:
    """当前时间（epoch 毫秒）——与表 ``started_at`` 字段量纲一致。"""
    return int(time.time() * 1000)


@dataclass(slots=True)
class Span:
    """一次调用片段的落库记录。"""

    span_id: str
    trace_id: str
    component: str
    entry: str
    started_at: int
    parent_span_id: str = ""
    service: str = SERVICE
    http_method: str = ""
    status: str = "OK"
    status_code: int = 0
    tenant_id: str = ""
    user_id: str = ""
    role: str = ""
    client_ip: str = ""
    elapsed_ms: int = 0
    error_msg: str = ""

    def to_row(self) -> tuple[object, ...]:
        """转成与 :data:`COLUMNS` 顺序一致的元组，供批量 INSERT 使用。"""
        return (
            self.span_id,
            self.trace_id,
            self.parent_span_id,
            self.service,
            self.component,
            self.entry,
            self.http_method,
            self.status,
            self.status_code,
            self.tenant_id,
            self.user_id,
            self.role,
            self.client_ip,
            self.elapsed_ms,
            # 截断错误摘要，避免超长文本撑爆字段
            self.error_msg[:_ERROR_MSG_MAX],
            self.started_at,
        )
