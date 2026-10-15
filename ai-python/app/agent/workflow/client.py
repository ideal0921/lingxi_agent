"""Java 编排域 gRPC 客户端（M4-4.8）：读取工作流版本与灰度规则、按版本取图定义。

- **惰性建连**：未真正调用前不建 channel（沿用 :class:`app.agent.tools.business.BusinessToolClient` 的范式，
  线程安全复用同一 channel）；
- **阻塞调用放入线程**：gRPC 同步 stub 经 ``asyncio.to_thread`` 执行，避免阻塞事件循环；
- 失败一律抛 :class:`WorkflowClientError`（不泄漏 gRPC 细节），由调用方降级回内置图。
"""

from __future__ import annotations

import asyncio
import logging
import sys
import threading
from collections.abc import Callable
from pathlib import Path
from typing import Any

import grpc

from app.agent.workflow.models import WorkflowSnapshot

logger = logging.getLogger(__name__)

# 生成契约所在目录（scripts/gen-proto.sh 输出），需在导入生成的 stub 前加入 sys.path。
# 本文件位于 ai-python/app/agent/workflow/ → parents[0]=workflow、[1]=agent、[2]=app、[3]=ai-python
_PROTO_DIR = Path(__file__).resolve().parents[3] / "proto"
if str(_PROTO_DIR) not in sys.path:
    sys.path.insert(0, str(_PROTO_DIR))


class WorkflowClientError(RuntimeError):
    """编排域调用失败（网络/超时/服务端错误），由调用方降级为内置图。"""


class WorkflowClient:
    """编排域 gRPC 客户端（惰性建连，线程安全复用同一 channel）。"""

    def __init__(
        self,
        target: str,
        *,
        timeout_seconds: float = 3.0,
        stub_factory: Callable[[], Any] | None = None,
    ) -> None:
        """
        Args:
            target: Java 编排域地址（与业务域同一 gRPC 端口）。
            timeout_seconds: 单次调用超时（秒）。
            stub_factory: 自定义 stub 工厂（离线测试注入假 stub；``None`` 时走真实 gRPC channel）。
        """
        self._target = target
        self._timeout = timeout_seconds
        self._stub_factory = stub_factory
        self._channel: Any = None
        self._stub: Any = None
        self._lock = threading.Lock()

    def _get_stub(self) -> Any:
        """惰性创建 channel/stub（双检锁；未真正调用前不建连）。"""
        if self._stub is not None:
            return self._stub
        with self._lock:
            if self._stub is None:
                if self._stub_factory is not None:
                    self._stub = self._stub_factory()
                else:
                    import workflow_pb2_grpc as workflow_grpc  # 延迟导入：无调用场景不加载 gRPC 栈

                    self._channel = grpc.insecure_channel(self._target)
                    self._stub = workflow_grpc.WorkflowServiceStub(self._channel)
                    logger.info("编排域 gRPC 通道已建立 target=%s", self._target)
        return self._stub

    def close(self) -> None:
        """释放 channel（进程退出或测试收尾时调用）。"""
        with self._lock:
            if self._channel is not None:
                self._channel.close()
            self._channel = None
            self._stub = None

    async def get_workflow(self, workflow_id: str) -> WorkflowSnapshot:
        """读取工作流快照（版本计数与灰度规则）。"""
        return await asyncio.to_thread(self._get_workflow_sync, workflow_id)

    async def get_version(self, workflow_id: str, version: int) -> str:
        """读取指定版本的图定义正文。"""
        return await asyncio.to_thread(self._get_version_sync, workflow_id, version)

    def _get_workflow_sync(self, workflow_id: str) -> WorkflowSnapshot:
        import workflow_pb2 as workflow_pb

        try:
            reply = self._get_stub().GetWorkflow(
                workflow_pb.GetWorkflowRequest(workflow_id=workflow_id), timeout=self._timeout
            )
        except grpc.RpcError as exc:
            raise WorkflowClientError(f"读取工作流失败：{exc.code()}") from exc
        workflow = reply.workflow
        if not workflow.workflow_id:
            raise WorkflowClientError("工作流不存在或响应为空")
        return WorkflowSnapshot(
            workflow_id=workflow.workflow_id,
            published_version=workflow.published_version,
            latest_version=workflow.latest_version,
            canary_version=workflow.canary_version,
            canary_percent=workflow.canary_percent,
            status=workflow.status,
        )

    def _get_version_sync(self, workflow_id: str, version: int) -> str:
        import workflow_pb2 as workflow_pb

        try:
            reply = self._get_stub().GetVersion(
                workflow_pb.GetVersionRequest(workflow_id=workflow_id, version=version),
                timeout=self._timeout,
            )
        except grpc.RpcError as exc:
            raise WorkflowClientError(f"读取工作流版本失败：{exc.code()}") from exc
        if not reply.ok:
            raise WorkflowClientError(f"版本不可用：{reply.reason or version}")
        return reply.definition_json
