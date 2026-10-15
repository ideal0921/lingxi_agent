"""坐席快捷回复 gRPC 同步服务端（M4-4.6）：Python 侧首个 gRPC 服务端。

为什么用 ``grpc.aio`` 而非同步 ``ThreadPoolExecutor``：
``LLMGateway.generate`` 是协程，``grpc.aio`` 服务端可在 FastAPI 事件循环内直接 ``await``，
**天然不阻塞事件循环、且无每请求线程**（压测关注的是「任务数回落」，而非 OS 线程数）。
若改用同步 servicer，则必须把协程 ``run_coroutine_threadsafe`` 桥接回 FastAPI 循环，
反而重造阻塞风险与线程泄漏面。并发隔离由 :class:`ConcurrencyGate`（``asyncio.Semaphore``）
表达，``maximum_concurrent_rpcs`` 作硬顶（超出由 gRPC 层直接拒绝，作为第二道保险）。

降级约定：闸门满/生成超时 → 以 **gRPC OK + ``degraded=true``** 返回（非错误状态），
便于 Java 侧区分「过载」与「故障」，据此回落 Kafka 异步链路。
"""

from __future__ import annotations

import asyncio
import logging
import sys
import time
from pathlib import Path
from typing import Any

import grpc

from app.agent.state import ChatMessage
from app.agent.suggest import (
    ConcurrencyGate,
    build_suggest_prompt,
    elapsed_ms,
    new_suggest_masker,
    parse_candidates,
)

logger = logging.getLogger(__name__)

# 生成的契约所在目录（scripts/gen-proto.sh 输出），导入 stub 前加入 sys.path。
# 本文件位于 ai-python/app/sync/ → parents[0]=sync、[1]=app、[2]=ai-python
_PROTO_DIR = Path(__file__).resolve().parents[2] / "proto"
if str(_PROTO_DIR) not in sys.path:
    sys.path.insert(0, str(_PROTO_DIR))

REASON_BUSY = "BUSY"
REASON_TIMEOUT = "TIMEOUT"
REASON_UNAVAILABLE = "UNAVAILABLE"


def _build_history(raw_history: Any) -> list[ChatMessage]:
    """把请求中的上下文转为消息列表（仅保留 user/agent 角色，避免脏数据进提示词）。"""
    messages: list[ChatMessage] = []
    for turn in raw_history or []:
        role = str(getattr(turn, "role", "") or "")
        content = str(getattr(turn, "content", "") or "")
        if role in ("user", "agent") and content:
            messages.append(ChatMessage(role=role, content=content))
    return messages


class SuggestServicer:
    """``AgentSyncService.SuggestReplies`` 实现（手工实现，避免依赖生成基类的导入顺序）。"""

    def __init__(self, *, gateway: Any, config: Any, gate: ConcurrencyGate) -> None:
        self._gateway = gateway
        self._config = config
        self._gate = gate

    async def SuggestReplies(self, request: Any, context: Any) -> Any:
        # 方法名沿用生成的 gRPC 契约命名（N802 属该场景约定，故不启用该规则）
        """同步生成回复建议；闸门满/超时以 ``degraded`` 返回（不抛异常）。"""
        import agent_sync_pb2 as agent_pb  # 函数内导入：无同步调用场景不加载

        started = time.perf_counter()
        if not await self._gate.acquire():
            logger.info(
                "快捷回复闸门已满，立即降级 requestId=%s sessionId=%s limit=%d",
                request.request_id,
                request.session_id,
                self._gate.limit,
            )
            return agent_pb.GenerateSuggestResponse(
                ok=False, degraded=True, degrade_reason=REASON_BUSY, elapsed_ms=elapsed_ms(started)
            )

        try:
            return await self._generate(request, agent_pb, started)
        except Exception:  # 生成异常一律转降级，不让调用方看到 gRPC 错误
            logger.exception("快捷回复生成失败 requestId=%s", request.request_id)
            return agent_pb.GenerateSuggestResponse(
                ok=False,
                degraded=True,
                degrade_reason=REASON_UNAVAILABLE,
                elapsed_ms=elapsed_ms(started),
            )
        finally:
            self._gate.release()

    async def _generate(self, request: Any, agent_pb: Any, started: float) -> Any:
        max_candidates = request.max_suggestions or self._config.suggest_max_candidates
        masker = new_suggest_masker(
            enabled=self._config.pii_enabled, mask_address=self._config.pii_mask_address
        )
        history = [
            ChatMessage(role=message.role, content=masker.mask(message.content))
            for message in _build_history(request.history)
        ]
        prompt = build_suggest_prompt(
            masker.mask(request.summary or ""), history, masker.mask(request.intent or ""), max_candidates
        )
        try:
            text = await asyncio.wait_for(
                self._gateway.generate(prompt), timeout=self._config.suggest_timeout_seconds
            )
        except TimeoutError:
            self._gate.mark_timeout()
            logger.warning(
                "快捷回复生成超时 requestId=%s 上限=%.1fs",
                request.request_id,
                self._config.suggest_timeout_seconds,
            )
            return agent_pb.GenerateSuggestResponse(
                ok=False, degraded=True, degrade_reason=REASON_TIMEOUT, elapsed_ms=elapsed_ms(started)
            )
        # 返回前还原 PII（模型可能回显了脱敏后的内容）
        candidates = [masker.restore(item) for item in parse_candidates(text, max_candidates)]
        logger.info(
            "快捷回复生成完成 requestId=%s 候选=%d 耗时=%dms",
            request.request_id,
            len(candidates),
            elapsed_ms(started),
        )
        return agent_pb.GenerateSuggestResponse(
            ok=bool(candidates),
            suggestions=candidates,
            degraded=False,
            elapsed_ms=elapsed_ms(started),
        )


def create_sync_server(*, gateway: Any, config: Any, gate: ConcurrencyGate) -> grpc.aio.Server:
    """创建（但未启动）gRPC 同步服务端；端口与并发硬顶取自配置。"""
    import agent_sync_pb2_grpc as agent_grpc  # 函数内导入：惰性加载生成的契约

    server = grpc.aio.server(
        maximum_concurrent_rpcs=config.suggest_max_concurrent_rpcs  # 第二道保险（硬顶）
    )
    agent_grpc.add_AgentSyncServiceServicer_to_server(SuggestServicer(gateway=gateway, config=config, gate=gate), server)
    bound = server.add_insecure_port(f"0.0.0.0:{config.suggest_grpc_port}")
    if bound == 0:
        raise RuntimeError(f"快捷回复 gRPC 端口绑定失败：{config.suggest_grpc_port}")
    logger.info(
        "快捷回复 gRPC 服务端已创建 port=%d 闸门=%d 并发硬顶=%d",
        bound,
        gate.limit,
        config.suggest_max_concurrent_rpcs,
    )
    return server
