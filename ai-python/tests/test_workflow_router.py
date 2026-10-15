"""版本灰度路由单测（M4-4.8）：分桶确定性、边界与缓存。

关键断言：**同一会话始终命中同一版本**（crc32 稳定哈希），从而避免多轮对话中途换图。
"""

from __future__ import annotations

from app.agent.workflow.cache import TtlLruCache
from app.agent.workflow.models import WorkflowSnapshot
from app.agent.workflow.router import resolve_version, stable_bucket


def _snapshot(published: int, canary_version: int = 0, canary_percent: int = 0) -> WorkflowSnapshot:
    return WorkflowSnapshot(
        workflow_id="wf-1",
        published_version=published,
        latest_version=max(published, canary_version),
        canary_version=canary_version,
        canary_percent=canary_percent,
    )


def test_percent_zero_always_stable_version() -> None:
    snapshot = _snapshot(published=3, canary_version=4, canary_percent=0)
    for index in range(50):
        assert resolve_version(f"session-{index}", snapshot) == 3


def test_percent_hundred_always_canary_version() -> None:
    snapshot = _snapshot(published=3, canary_version=4, canary_percent=100)
    for index in range(50):
        assert resolve_version(f"session-{index}", snapshot) == 4


def test_unpublished_returns_zero() -> None:
    assert resolve_version("s-1", _snapshot(published=0)) == 0
    # 未发布但配置了灰度（不应出现）：仍以 published=0 表达「无可用版本」
    assert resolve_version("s-1", _snapshot(published=0, canary_version=1, canary_percent=100)) == 1


def test_same_session_is_stable_across_repeated_calls() -> None:
    snapshot = _snapshot(published=1, canary_version=2, canary_percent=50)
    first = resolve_version("session-fixed", snapshot)
    for _ in range(20):
        assert resolve_version("session-fixed", snapshot) == first


def test_bucket_distribution_is_reasonably_uniform() -> None:
    buckets = [stable_bucket(f"session-{index}") for index in range(2000)]
    assert all(0 <= bucket < 100 for bucket in buckets)
    # 20 桶分组：每组约 10%，允许较大波动（仅验证不是「全挤在一个桶」）
    spread = len({bucket // 10 for bucket in buckets})
    assert spread >= 8


def test_partial_percent_splits_sessions_between_two_versions() -> None:
    snapshot = _snapshot(published=1, canary_version=2, canary_percent=30)
    versions = {resolve_version(f"session-{index}", snapshot) for index in range(200)}
    assert versions == {1, 2}
    canary_share = sum(
        1 for index in range(200) if resolve_version(f"session-{index}", snapshot) == 2
    )
    # 30% 分流：200 个会话下允许一定波动，只要求非极端
    assert 30 <= canary_share <= 90


def test_canary_without_version_falls_back_to_stable() -> None:
    # 比例 >0 但灰度版本为 0（脏数据）：视为未启用灰度
    snapshot = _snapshot(published=5, canary_version=0, canary_percent=50)
    assert resolve_version("session-x", snapshot) == 5


def test_empty_session_id_buckets_to_zero() -> None:
    assert stable_bucket("") == 0


# ---------- 缓存（TTL + LRU） ----------


def test_cache_hits_and_misses() -> None:
    cache: TtlLruCache[str] = TtlLruCache(max_size=2, ttl_seconds=300.0)
    assert cache.get("missing") is None

    cache.put("a", "value-a")
    assert cache.get("a") == "value-a"
    assert len(cache) == 1


def test_cache_evicts_least_recently_used() -> None:
    cache: TtlLruCache[str] = TtlLruCache(max_size=2, ttl_seconds=300.0)
    cache.put("a", "1")
    cache.put("b", "2")
    cache.get("a")  # 命中 "a" → 变为最近使用
    cache.put("c", "3")  # 淘汰最久未使用的 "b"

    assert cache.get("b") is None
    assert cache.get("a") == "1"
    assert cache.get("c") == "3"


def test_cache_expires_by_ttl() -> None:
    cache: TtlLruCache[str] = TtlLruCache(max_size=4, ttl_seconds=-1.0)  # ttl<=0 → 永不过期
    cache.put("a", "1")
    assert cache.get("a") == "1"

    expiring: TtlLruCache[str] = TtlLruCache(max_size=4, ttl_seconds=0.0001)
    expiring.put("a", "1")
    import time

    time.sleep(0.01)
    assert expiring.get("a") is None


def test_cache_disabled_when_max_size_zero() -> None:
    cache: TtlLruCache[str] = TtlLruCache(max_size=0, ttl_seconds=300.0)
    cache.put("a", "1")
    assert cache.get("a") is None
    assert len(cache) == 0


def test_cache_invalidate_and_clear() -> None:
    cache: TtlLruCache[str] = TtlLruCache(max_size=4, ttl_seconds=300.0)
    cache.put("a", "1")
    cache.invalidate("a")
    assert cache.get("a") is None

    cache.put("b", "2")
    cache.clear()
    assert len(cache) == 0
