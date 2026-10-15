"""用户级长期记忆存储（M4-4.4）：以 Redis KV 保存「每用户 1 条演进画像摘要」。

定位（《工程设计与开发规划》5.4 之 4.4）：会话内记忆由 M3-3.11 的长程摘要承担；
本模块补齐**跨会话**记忆——把用户跨会话都值得记住的长期信息（偏好、身份角色、
长期约束、反复诉求）沉淀为画像，按 ``tenant_id`` + ``user_id`` 严格隔离。

设计要点：
- **异步非阻塞**：使用 ``redis.asyncio``（连接池随单例复用），不做同步阻塞调用；
- **严格隔离**：key 形如 ``lingxi:memory:profile:{tenant}:{user}``；``user_id`` 缺失时
  直接跳过读写（不落库、不注入），从源头杜绝跨用户串数据；
- **失败降级**：Redis 不可达或超时一律告警并返回空/失败，**绝不阻断问答主链路**
  （与项目既有「静默降级 + 告警」风格一致）；
- **TTL 兜底**：画像带过期时间，避免长期不活跃用户的僵尸数据无限堆积。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

logger = logging.getLogger(__name__)

# key 前缀（与 Java 侧共享 Redis 实例，靠前缀隔离命名空间）
MEMORY_KEY_PREFIX = "lingxi:memory:profile"


class UserProfileStore:
    """用户画像存储（Redis KV）。

    构造时可注入 ``client``（离线单测用假客户端），也可由 host/port 惰性自建异步客户端。
    """

    def __init__(
        self,
        *,
        client: Any = None,
        host: str = "127.0.0.1",
        port: int = 6379,
        db: int = 0,
        password: str = "",
        ttl_seconds: int = 2_592_000,
        timeout_seconds: float = 1.0,
        protocol: int = 2,
    ) -> None:
        self._client = client
        self._host = host
        self._port = port
        self._db = db
        self._password = password
        self._ttl = ttl_seconds
        self._timeout = timeout_seconds
        self._protocol = protocol

    # ---------- key 构造 ----------

    @staticmethod
    def profile_key(tenant_id: str, user_id: str) -> str | None:
        """构造画像 key；``user_id`` 为空时返回 ``None``（调用侧据此跳过记忆功能）。

        冒号是 key 的分隔符，需转义，避免租户/用户 ID 内含冒号时污染 key 层级。
        """
        if not user_id or not user_id.strip():
            return None
        tenant = (tenant_id or "").strip().replace(":", "_")
        user = user_id.strip().replace(":", "_")
        return f"{MEMORY_KEY_PREFIX}:{tenant}:{user}"

    # ---------- 读写 ----------

    async def get_profile(self, tenant_id: str, user_id: str) -> str:
        """读取用户画像；无画像、key 非法或 Redis 异常时返回空串（降级）。"""
        key = self.profile_key(tenant_id, user_id)
        if key is None:
            return ""
        try:
            client = self._get_client()
            value = await asyncio.wait_for(client.get(key), timeout=self._timeout)
        except Exception as exc:  # noqa: BLE001 - 记忆读取失败不得影响问答
            logger.warning("用户画像读取失败（降级为无记忆） key=%s 原因=%s", key, exc)
            return ""
        if value is None:
            return ""
        return value.decode("utf-8") if isinstance(value, bytes) else str(value)

    async def save_profile(self, tenant_id: str, user_id: str, profile: str) -> bool:
        """写入用户画像（带 TTL）；成功返回 True，key 非法或 Redis 异常返回 False（降级）。"""
        key = self.profile_key(tenant_id, user_id)
        if key is None:
            return False
        try:
            client = self._get_client()
            await asyncio.wait_for(
                client.set(key, profile, ex=self._ttl), timeout=self._timeout
            )
        except Exception as exc:  # noqa: BLE001 - 记忆写入失败不得影响问答
            logger.warning("用户画像写入失败（已跳过） key=%s 原因=%s", key, exc)
            return False
        logger.info("用户画像已更新 key=%s 长度=%d ttl=%ds", key, len(profile), self._ttl)
        return True

    async def close(self) -> None:
        """释放连接池（进程退出或测试收尾时调用）。"""
        if self._client is None:
            return
        try:
            await self._client.aclose()
        except Exception:  # 关闭失败不影响进程退出
            logger.warning("用户画像 Redis 客户端关闭失败", exc_info=True)
        self._client = None

    # ---------- 内部 ----------

    def _get_client(self) -> Any:
        """惰性创建异步客户端（首次真正读写时才建连）。"""
        if self._client is None:
            from redis.asyncio import Redis  # 函数内导入：无记忆场景不加载

            self._client = Redis(
                host=self._host,
                port=self._port,
                db=self._db,
                password=self._password or None,
                protocol=self._protocol,  # RESP2：兼容无 HELLO 的旧版 Redis（< 6.0）
                socket_connect_timeout=0.5,
                socket_timeout=self._timeout,
                health_check_interval=30,
                decode_responses=False,
            )
            logger.info(
                "用户画像 Redis 客户端已创建 %s:%s/%s", self._host, self._port, self._db
            )
        return self._client
