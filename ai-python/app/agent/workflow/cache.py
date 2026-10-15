"""TTL + LRU 缓存（M4-4.8）：供工作流快照与已编译图共用。

实现方式与 :class:`app.rag.ingest.embedder.CachedEmbedder` 保持一致（``OrderedDict``
天然具备 LRU 语义 + ``time.monotonic`` 过期判断），避免为同一诉求引入两套写法。
"""

from __future__ import annotations

import time
from collections import OrderedDict
from dataclasses import dataclass


@dataclass(slots=True)
class _Entry[ValueT]:
    """缓存条目：值 + 过期时间戳。"""

    value: ValueT
    expires_at: float


class TtlLruCache[ValueT]:
    """容量受限、按 TTL 过期的缓存。

    ``max_size<=0`` 表示禁用缓存（``get`` 恒返回 ``None``，``put`` 不生效），
    便于通过配置一键关闭。
    """

    def __init__(self, *, max_size: int = 32, ttl_seconds: float = 300.0) -> None:
        self._max_size = max(0, max_size)
        self._ttl = ttl_seconds
        self._entries: OrderedDict[str, _Entry[ValueT]] = OrderedDict()

    def get(self, key: str) -> ValueT | None:
        """取缓存值；过期条目就地清除。"""
        if self._max_size == 0:
            return None
        entry = self._entries.get(key)
        if entry is None:
            return None
        if self._is_expired(entry):
            del self._entries[key]
            return None
        self._entries.move_to_end(key)  # 命中即刷新 LRU 位置
        return entry.value

    def put(self, key: str, value: ValueT) -> None:
        """写入并淘汰最久未使用项。"""
        if self._max_size == 0:
            return
        self._entries[key] = _Entry(value=value, expires_at=time.monotonic() + self._ttl)
        self._entries.move_to_end(key)
        while len(self._entries) > self._max_size:
            self._entries.popitem(last=False)

    def invalidate(self, key: str) -> None:
        """按键清除（配置变更后主动失效用）。"""
        self._entries.pop(key, None)

    def clear(self) -> None:
        """清空全部条目。"""
        self._entries.clear()

    def __len__(self) -> int:
        return len(self._entries)

    def _is_expired(self, entry: _Entry[ValueT]) -> bool:
        """``ttl<=0`` 视为永不过期（仅受容量约束）。"""
        return self._ttl > 0 and time.monotonic() >= entry.expires_at
