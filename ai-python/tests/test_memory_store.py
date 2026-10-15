"""用户级长期记忆存储单测（M4-4.4，离线运行，**不真连 Redis**）。

覆盖：key 按 tenant+user 隔离与转义、缺 user_id 时不读写、读写异常/超时降级为空/False、
TTL 透传、连接池释放。
"""

from __future__ import annotations

import asyncio

from app.agent.memory_store import MEMORY_KEY_PREFIX, UserProfileStore


class _FakeRedis:
    """假异步 Redis 客户端：记录写入内容与 TTL，可模拟异常/超时。"""

    def __init__(self, *, fail: bool = False, delay: float = 0.0) -> None:
        self.data: dict[str, str] = {}
        self.ttls: dict[str, int] = {}
        self.closed = False
        self._fail = fail
        self._delay = delay

    async def get(self, key: str):
        if self._fail:
            raise ConnectionError("redis 不可用")
        if self._delay:
            await asyncio.sleep(self._delay)
        return self.data.get(key)

    async def set(self, key: str, value: str, ex: int | None = None) -> bool:
        if self._fail:
            raise ConnectionError("redis 不可用")
        if self._delay:
            await asyncio.sleep(self._delay)
        self.data[key] = value
        if ex is not None:
            self.ttls[key] = ex
        return True

    async def aclose(self) -> None:
        self.closed = True


def _store(client=None, **overrides) -> UserProfileStore:
    base = {"client": client or _FakeRedis(), "ttl_seconds": 3600, "timeout_seconds": 1.0}
    base.update(overrides)
    return UserProfileStore(**base)


class TestProfileKey:
    def test_isolates_by_tenant_and_user(self) -> None:
        assert UserProfileStore.profile_key("t1", "u1") == f"{MEMORY_KEY_PREFIX}:t1:u1"
        assert UserProfileStore.profile_key("t1", "u2") != UserProfileStore.profile_key("t2", "u1")
        assert UserProfileStore.profile_key("t1", "u1") != UserProfileStore.profile_key("t2", "u1")

    def test_escapes_separator(self) -> None:
        """冒号是 key 分隔符，需转义，避免租户/用户 ID 污染 key 层级。"""
        assert UserProfileStore.profile_key("a:b", "c:d") == f"{MEMORY_KEY_PREFIX}:a_b:c_d"

    def test_blank_user_id_returns_none(self) -> None:
        assert UserProfileStore.profile_key("t1", "") is None
        assert UserProfileStore.profile_key("t1", "   ") is None


class TestReadWrite:
    async def test_roundtrip_with_ttl(self) -> None:
        client = _FakeRedis()
        store = _store(client)

        assert await store.get_profile("t1", "u1") == "", "无画像时返回空串"
        assert await store.save_profile("t1", "u1", "偏好：货到付款") is True
        assert await store.get_profile("t1", "u1") == "偏好：货到付款"
        assert client.ttls[f"{MEMORY_KEY_PREFIX}:t1:u1"] == 3600, "写入需携带 TTL"

    async def test_key_isolated_between_users(self) -> None:
        client = _FakeRedis()
        store = _store(client)

        await store.save_profile("t1", "u1", "u1 的画像")
        await store.save_profile("t1", "u2", "u2 的画像")

        assert await store.get_profile("t1", "u1") == "u1 的画像"
        assert await store.get_profile("t1", "u2") == "u2 的画像"

    async def test_blank_user_id_does_not_touch_storage(self) -> None:
        client = _FakeRedis()
        store = _store(client)

        assert await store.get_profile("t1", "") == ""
        assert await store.save_profile("t1", "", "x") is False
        assert client.data == {}, "缺 user_id 时不得落库"


class TestDegradation:
    async def test_read_failure_returns_empty(self) -> None:
        store = _store(_FakeRedis(fail=True))
        assert await store.get_profile("t1", "u1") == "", "Redis 异常应降级为空画像"

    async def test_write_failure_returns_false(self) -> None:
        store = _store(_FakeRedis(fail=True))
        assert await store.save_profile("t1", "u1", "画像") is False, "Redis 异常应降级为写入失败"

    async def test_timeout_degrades(self) -> None:
        """超时同样降级（不能让 Redis 抖动拖慢问答主链路）。"""
        store = _store(_FakeRedis(delay=0.2), timeout_seconds=0.01)
        assert await store.get_profile("t1", "u1") == ""
        assert await store.save_profile("t1", "u1", "画像") is False

    async def test_close_releases_client(self) -> None:
        client = _FakeRedis()
        store = _store(client)
        await store.close()
        assert client.closed is True
