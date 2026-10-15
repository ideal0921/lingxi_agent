"""版本灰度路由（M4-4.8）：按会话稳定哈希把流量分流到稳定版/灰度版。

分桶用 ``crc32(session_id) % 100``：确定性、无外部依赖，且**同一会话始终命中同一版本**
（体验一致、问题可复现、避免多轮对话中途换图）。
"""

from __future__ import annotations

import zlib

from app.agent.workflow.models import WorkflowSnapshot

# 分桶粒度：0~99（百分比整数）
_BUCKETS = 100


def stable_bucket(session_id: str) -> int:
    """会话标识 → 稳定桶号（0~99）。

    空标识按 0 处理（调用方应保证有 session_id；此处不抛错以免中断对话）。
    """
    if not session_id:
        return 0
    return zlib.crc32(session_id.encode("utf-8")) % _BUCKETS


def resolve_version(session_id: str, snapshot: WorkflowSnapshot) -> int:
    """决定本次会话应加载的工作流版本号。

    Returns:
        版本号；``0`` 表示无可用版本（未发布/灰度规则未生效），调用方应回落内置图。
    """
    if (
        snapshot.canary_percent > 0
        and snapshot.canary_version > 0
        and stable_bucket(session_id) < snapshot.canary_percent
    ):
        return snapshot.canary_version
    return snapshot.published_version
