"""业务工具（M4-4.2）：AI 域经 gRPC 调用 Java 业务域，查询订单/物流、提交退换货。

- **租户由上下文注入**：``tenant_id`` 取自 :class:`ToolContext`（服务端注入），
  且不写入工具 schema（``additionalProperties=false`` 会拒绝模型自行传入的 tenant_id），从源头防越权；
- **阻塞调用放入线程**：gRPC 同步 stub 通过 ``asyncio.to_thread`` 执行，避免阻塞事件循环；
- **面向模型的自然语言结果**：状态枚举翻译为中文并附关键字段，模型可直接引用而无需猜测；
- 失败一律抛 :class:`BusinessToolError`，由执行器统一做重试/降级与审计。
"""

from __future__ import annotations

import asyncio
import logging
import sys
import threading
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import Any

import grpc

from app.agent.tools.spec import ToolContext, ToolSpec

logger = logging.getLogger(__name__)

# 生成契约所在目录（scripts/gen-proto.sh 输出），需在导入生成的 stub 前加入 sys.path。
# 本文件位于 ai-python/app/agent/tools/ → parents[0]=tools、[1]=agent、[2]=app、[3]=ai-python
_PROTO_DIR = Path(__file__).resolve().parents[3] / "proto"
if str(_PROTO_DIR) not in sys.path:
    sys.path.insert(0, str(_PROTO_DIR))

_ORDER_STATUS_CN = {
    "PAID": "已付款待发货",
    "SHIPPED": "已发货",
    "DELIVERED": "已签收",
    "REFUNDING": "退款处理中",
    "CLOSED": "已关闭",
}
_LOGISTICS_STATUS_CN = {
    "PENDING": "待揽收",
    "PICKED": "已揽收",
    "IN_TRANSIT": "运输中",
    "OUT_FOR_DELIVERY": "派送中",
    "DELIVERED": "已签收",
    "EXCEPTION": "物流异常",
}
_RETURN_TYPE_CN = {"REFUND": "仅退款", "EXCHANGE": "换货"}


class BusinessToolError(RuntimeError):
    """业务工具调用失败（网络/超时/服务端错误），由执行器转为重试或降级。"""


class BusinessToolClient:
    """Java 业务域 gRPC 客户端（惰性建连，线程安全复用同一 channel）。"""

    def __init__(self, target: str, *, timeout_seconds: float = 5.0) -> None:
        self._target = target
        self._timeout = timeout_seconds
        self._channel: Any = None
        self._stub: Any = None
        self._lock = threading.Lock()

    def _get_stub(self) -> Any:
        """惰性创建 channel/stub（双检锁；未真正调用前不建连）。"""
        if self._stub is not None:
            return self._stub
        with self._lock:
            if self._stub is None:
                import business_pb2_grpc as business_grpc  # 延迟导入：无 gRPC 调用场景不加载

                self._channel = grpc.insecure_channel(self._target)
                self._stub = business_grpc.BusinessToolServiceStub(self._channel)
                logger.info("业务工具 gRPC 通道已建立 target=%s", self._target)
        return self._stub

    def close(self) -> None:
        """释放 channel（进程退出或测试收尾时调用）。"""
        with self._lock:
            if self._channel is not None:
                self._channel.close()
            self._channel = None
            self._stub = None

    # ---------- 对外异步方法（阻塞部分放入线程） ----------

    async def get_order(self, tenant_id: str, order_no: str) -> dict[str, Any]:
        return await asyncio.to_thread(self._get_order_sync, tenant_id, order_no)

    async def get_logistics(self, tenant_id: str, order_no: str) -> dict[str, Any]:
        return await asyncio.to_thread(self._get_logistics_sync, tenant_id, order_no)

    async def submit_return(
        self, tenant_id: str, order_no: str, return_type: str, reason: str
    ) -> dict[str, Any]:
        return await asyncio.to_thread(self._submit_return_sync, tenant_id, order_no, return_type, reason)

    # ---------- 同步实现 ----------

    def _get_order_sync(self, tenant_id: str, order_no: str) -> dict[str, Any]:
        import business_pb2 as business_pb

        try:
            reply = self._get_stub().GetOrder(
                business_pb.GetOrderRequest(tenant_id=tenant_id, order_no=order_no), timeout=self._timeout
            )
        except grpc.RpcError as exc:
            raise BusinessToolError(f"订单查询失败：{exc.code()}") from exc
        return {
            "found": reply.found,
            "reason": reply.reason,
            "order_no": reply.order_no,
            "status": _ORDER_STATUS_CN.get(reply.status, reply.status),
            "amount": reply.amount,
            "created_at_ms": reply.created_at_ms,
            "items_summary": reply.items_summary,
        }

    def _get_logistics_sync(self, tenant_id: str, order_no: str) -> dict[str, Any]:
        import business_pb2 as business_pb

        try:
            reply = self._get_stub().GetLogistics(
                business_pb.GetLogisticsRequest(tenant_id=tenant_id, order_no=order_no), timeout=self._timeout
            )
        except grpc.RpcError as exc:
            raise BusinessToolError(f"物流查询失败：{exc.code()}") from exc
        return {
            "found": reply.found,
            "reason": reply.reason,
            "order_no": reply.order_no,
            "carrier": reply.carrier,
            "tracking_no": reply.tracking_no,
            "status": _LOGISTICS_STATUS_CN.get(reply.status, reply.status),
            "latest_event": reply.latest_event,
            "updated_at_ms": reply.updated_at_ms,
        }

    def _submit_return_sync(self, tenant_id: str, order_no: str, return_type: str, reason: str) -> dict[str, Any]:
        import business_pb2 as business_pb

        try:
            reply = self._get_stub().SubmitReturn(
                business_pb.SubmitReturnRequest(
                    tenant_id=tenant_id, order_no=order_no, return_type=return_type, reason=reason
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as exc:
            raise BusinessToolError(f"退换货申请失败：{exc.code()}") from exc
        return {
            "ok": reply.ok,
            "reason": reply.reason,
            "return_id": reply.return_id,
            "status": reply.status,
        }


# ---------- JSON Schema（字段白名单：不包含 tenant_id，模型无法指定租户） ----------

_ORDER_NO_FIELD: dict[str, Any] = {
    "type": "string",
    "minLength": 6,
    "maxLength": 32,
    "description": "订单号，例如 LN20240815001",
}
_ORDER_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {"order_no": _ORDER_NO_FIELD},
    "required": ["order_no"],
    "additionalProperties": False,
}
_SUBMIT_RETURN_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "order_no": _ORDER_NO_FIELD,
        "return_type": {
            "type": "string",
            "enum": ["REFUND", "EXCHANGE"],
            "description": "REFUND=仅退款；EXCHANGE=换货",
        },
        "reason": {"type": "string", "maxLength": 200, "description": "用户申请原因（可选）"},
    },
    "required": ["order_no", "return_type"],
    "additionalProperties": False,
}


def _format_time(ms: int, fmt: str = "%Y-%m-%d %H:%M") -> str:
    """毫秒时间戳 → 本地可读时间；非法值返回空串。"""
    if not ms:
        return ""
    try:
        return datetime.fromtimestamp(ms / 1000).strftime(fmt)
    except (OverflowError, OSError, ValueError):
        return ""


def make_tool_handlers(
    client: BusinessToolClient,
) -> dict[str, Callable[[dict[str, Any], ToolContext], Any]]:
    """构造与给定客户端绑定的工具处理器（闭包绑定，避免全局可变状态）。"""

    async def get_order(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        reply = await client.get_order(ctx.tenant_id, args["order_no"])
        if not reply.get("found"):
            return {"found": False, "说明": reply.get("reason") or "订单不存在或不属于当前用户"}
        return {
            "found": True,
            "订单号": reply["order_no"],
            "状态": reply["status"],
            "金额": f"{reply['amount']} 元",
            "商品": reply["items_summary"],
            "下单时间": _format_time(reply["created_at_ms"]),
        }

    async def get_logistics(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        reply = await client.get_logistics(ctx.tenant_id, args["order_no"])
        if not reply.get("found"):
            return {"found": False, "说明": reply.get("reason") or "暂无该订单的物流信息"}
        return {
            "found": True,
            "订单号": reply["order_no"],
            "承运商": reply["carrier"],
            "运单号": reply["tracking_no"],
            "物流状态": reply["status"],
            "最新动态": reply["latest_event"],
            "更新时间": _format_time(reply["updated_at_ms"]),
        }

    async def submit_return(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        reply = await client.submit_return(
            ctx.tenant_id, args["order_no"], args["return_type"], args.get("reason", "")
        )
        if not reply.get("ok"):
            return {"ok": False, "说明": reply.get("reason") or "退换货申请未通过"}
        return {
            "ok": True,
            "受理单号": reply["return_id"],
            "类型": _RETURN_TYPE_CN.get(args["return_type"], args["return_type"]),
            "状态": "已受理",
        }

    return {"get_order": get_order, "get_logistics": get_logistics, "submit_return": submit_return}


def build_business_tools(client: BusinessToolClient, *, degrade_message: str = "") -> list[ToolSpec]:
    """构建三个业务工具（查订单 / 查物流 / 提交退换货）。"""
    handlers = make_tool_handlers(client)
    return [
        ToolSpec(
            name="get_order",
            description="查询订单详情（状态、金额、商品、下单时间）。用户询问订单进度、金额、买了什么时使用。",
            parameters=_ORDER_SCHEMA,
            handler=handlers["get_order"],
            degrade_message=degrade_message,
        ),
        ToolSpec(
            name="get_logistics",
            description="查询订单物流（承运商、运单号、最新动态）。用户询问包裹到哪了、什么时候能到时使用。",
            parameters=_ORDER_SCHEMA,
            handler=handlers["get_logistics"],
            degrade_message=degrade_message,
        ),
        ToolSpec(
            name="submit_return",
            description="为用户提交退换货申请。用户明确要求退款或换货，且已确认订单号时使用。",
            parameters=_SUBMIT_RETURN_SCHEMA,
            handler=handlers["submit_return"],
            degrade_message=degrade_message,
        ),
    ]
