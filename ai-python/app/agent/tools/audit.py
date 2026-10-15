"""工具调用全量审计（M4-4.3）：结构化日志 + Kafka 审计事件。

- **全量**：无论成功、失败、超时降级，还是「调用前被拒」（未注册/参数非法），都会记录一条；
- **脱敏**：入参与结果在记录前经 PII 脱敏器处理（若上下文提供），并做长度截断；
- **可追溯但不冗余**：结果只保留长度与摘要（sha256 前 16 位），避免审计流变成大字段搬运；
- 审计不影响主链路：日志与事件投递异常均被捕获，不打断工具调用结果回填。
"""

from __future__ import annotations

import hashlib
import json
import logging
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from typing import Any

logger = logging.getLogger(__name__)

# 审计字段长度上限（入参/错误原因），避免大对象随事件膨胀
MAX_FIELD_CHARS = 500
MAX_DIGEST_SOURCE_CHARS = 4000


@dataclass(slots=True)
class ToolAuditRecord:
    """一条工具调用审计记录。"""

    ts: str
    session_id: str
    tenant_id: str
    message_id: str
    tool: str
    invocation_id: str
    ok: bool
    degraded: bool
    rejected: bool
    attempts: int
    duration_ms: float
    arguments: str
    error: str = ""
    result_chars: int = 0
    result_digest: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def _mask(value: str, masker: Any) -> str:
    """可选的 PII 脱敏（不改变原值长度语义，仅替换敏感片段）。

    ``PiiMasker.mask(text) -> str`` 把敏感片段替换为占位符；脱敏器缺失或异常时
    **返回原文**（审计为空不应阻断调用链，但脱敏失败必须告警——审计事件会流向
    Kafka，原文泄露即为隐私事故）。
    """
    if not value:
        return ""
    if masker is not None:
        try:
            return masker.mask(value)
        except Exception:  # noqa: BLE001 - 脱敏失败不阻断审计
            logger.warning("审计字段脱敏失败，按原文截断记录")
    return value


def build_record(
    *,
    tool: str,
    invocation_id: str,
    session_id: str,
    tenant_id: str,
    message_id: str,
    arguments: str,
    ok: bool,
    degraded: bool,
    rejected: bool,
    attempts: int,
    duration_ms: float,
    error: str = "",
    result: str = "",
    masker: Any = None,
) -> ToolAuditRecord:
    """组装审计记录（含脱敏、截断与结果摘要）。"""
    digest_source = _mask((result or "")[:MAX_DIGEST_SOURCE_CHARS], masker)
    return ToolAuditRecord(
        ts=datetime.now(UTC).isoformat(timespec="milliseconds"),
        session_id=session_id,
        tenant_id=tenant_id,
        message_id=message_id,
        tool=tool,
        invocation_id=invocation_id,
        ok=ok,
        degraded=degraded,
        rejected=rejected,
        attempts=attempts,
        duration_ms=round(duration_ms, 1),
        arguments=_mask(arguments or "", masker)[:MAX_FIELD_CHARS],
        error=error[:MAX_FIELD_CHARS],
        result_chars=len(result or ""),
        result_digest=hashlib.sha256(digest_source.encode("utf-8")).hexdigest()[:16] if digest_source else "",
    )


class ToolAuditor:
    """审计出口：结构化日志（始终）+ Kafka 审计主题（可选）。"""

    def __init__(self, *, producer: Any = None, topic: str = "", enabled: bool = True) -> None:
        self._producer = producer
        self._topic = topic
        self._enabled = enabled

    async def record(self, record: ToolAuditRecord) -> None:
        """记录一条审计；异常一律吞掉并记日志，绝不影响工具调用主链路。"""
        payload = record.to_dict()
        logger.info("tool_audit %s", json.dumps(payload, ensure_ascii=False))
        if not self._enabled or self._producer is None or not self._topic:
            return
        try:
            await self._producer.publish(self._topic, payload, key=record.session_id or record.tool)
        except Exception:
            logger.exception("工具审计事件投递失败 tool=%s", record.tool)
