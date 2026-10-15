"""编排域 gRPC 客户端单测（M4-4.8，离线：注入假 stub，不建真实 channel）。

覆盖：快照字段映射、版本正文读取、业务拒绝（``ok=false``）与通道故障（``RpcError``）
一律映射为 :class:`WorkflowClientError`（不向上层泄漏 gRPC 细节），以及惰性建连语义。
"""

from __future__ import annotations

from types import SimpleNamespace

import grpc
import pytest

from app.agent.workflow.client import WorkflowClient, WorkflowClientError


class _FakeRpcError(grpc.RpcError):
    """模拟 gRPC 调用失败（带状态码）。"""

    def __init__(self, code: grpc.StatusCode = grpc.StatusCode.UNAVAILABLE) -> None:
        super().__init__()
        self._code = code

    def code(self) -> grpc.StatusCode:
        return self._code


class _FakeStub:
    """假 stub：记录调用参数并返回预设响应/错误。"""

    def __init__(
        self,
        *,
        workflow: object | None = None,
        version: object | None = None,
        workflow_error: Exception | None = None,
        version_error: Exception | None = None,
    ) -> None:
        self._workflow = workflow
        self._version = version
        self._workflow_error = workflow_error
        self._version_error = version_error
        self.calls: list[dict] = []

    def GetWorkflow(self, request: object, timeout: float | None = None) -> object:
        self.calls.append({"method": "GetWorkflow", "request": request, "timeout": timeout})
        if self._workflow_error is not None:
            raise self._workflow_error
        return self._workflow

    def GetVersion(self, request: object, timeout: float | None = None) -> object:
        self.calls.append({"method": "GetVersion", "request": request, "timeout": timeout})
        if self._version_error is not None:
            raise self._version_error
        return self._version


def _workflow_snapshot_reply(
    *,
    workflow_id: str = "wf-1",
    published: int = 1,
    latest: int = 2,
    canary_version: int = 2,
    canary_percent: int = 30,
    status: str = "ENABLED",
) -> object:
    workflow = SimpleNamespace(
        workflow_id=workflow_id,
        published_version=published,
        latest_version=latest,
        canary_version=canary_version,
        canary_percent=canary_percent,
        status=status,
    )
    return SimpleNamespace(workflow=workflow)


async def test_get_workflow_maps_snapshot_fields() -> None:
    stub = _FakeStub(workflow=_workflow_snapshot_reply())
    client = WorkflowClient("127.0.0.1:9090", timeout_seconds=1.5, stub_factory=lambda: stub)

    snapshot = await client.get_workflow("wf-1")

    assert snapshot.workflow_id == "wf-1"
    assert snapshot.published_version == 1
    assert snapshot.latest_version == 2
    assert snapshot.canary_version == 2
    assert snapshot.canary_percent == 30
    assert snapshot.status == "ENABLED"
    # 超时按配置透传
    assert stub.calls[0]["timeout"] == 1.5


async def test_get_workflow_rejects_blank_response() -> None:
    stub = _FakeStub(workflow=_workflow_snapshot_reply(workflow_id=""))
    client = WorkflowClient("127.0.0.1:9090", stub_factory=lambda: stub)

    with pytest.raises(WorkflowClientError):
        await client.get_workflow("wf-missing")


async def test_get_workflow_maps_rpc_error_without_leaking_details() -> None:
    stub = _FakeStub(workflow_error=_FakeRpcError(grpc.StatusCode.UNAVAILABLE))
    client = WorkflowClient("127.0.0.1:9090", stub_factory=lambda: stub)

    with pytest.raises(WorkflowClientError) as excinfo:
        await client.get_workflow("wf-1")
    assert "读取工作流失败" in str(excinfo.value)
    assert "UNAVAILABLE" in str(excinfo.value)


async def test_get_version_returns_definition_json() -> None:
    stub = _FakeStub(
        version=SimpleNamespace(ok=True, reason="", definition_json='{"nodes": [], "edges": []}')
    )
    client = WorkflowClient("127.0.0.1:9090", stub_factory=lambda: stub)

    raw = await client.get_version("wf-1", 2)

    assert raw == '{"nodes": [], "edges": []}'
    assert stub.calls[0]["request"].version == 2


async def test_get_version_maps_business_rejection() -> None:
    stub = _FakeStub(version=SimpleNamespace(ok=False, reason="版本不存在：9", definition_json=""))
    client = WorkflowClient("127.0.0.1:9090", stub_factory=lambda: stub)

    with pytest.raises(WorkflowClientError) as excinfo:
        await client.get_version("wf-1", 9)
    assert "版本不存在" in str(excinfo.value)


async def test_get_version_maps_rpc_error() -> None:
    stub = _FakeStub(version_error=_FakeRpcError(grpc.StatusCode.DEADLINE_EXCEEDED))
    client = WorkflowClient("127.0.0.1:9090", stub_factory=lambda: stub)

    with pytest.raises(WorkflowClientError):
        await client.get_version("wf-1", 1)


async def test_stub_is_created_lazily() -> None:
    created: list[int] = []

    def factory() -> _FakeStub:
        created.append(1)
        return _FakeStub(workflow=_workflow_snapshot_reply())

    client = WorkflowClient("127.0.0.1:9090", stub_factory=factory)
    assert created == []  # 未调用前不建连

    await client.get_workflow("wf-1")
    assert created == [1]

    await client.get_workflow("wf-1")  # 复用同一 stub
    assert created == [1]


def test_close_is_safe_without_calls() -> None:
    client = WorkflowClient("127.0.0.1:9090", stub_factory=lambda: _FakeStub())
    client.close()  # 未建连时关闭不应抛错
