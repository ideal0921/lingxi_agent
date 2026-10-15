"""多工作流运行时（M4-4.8）：按工作流版本加载编排图并路由版本灰度。

模块职责：
- :mod:`models`：图定义模型与解析（与 Java 编排域同一份 JSON Schema）；
- :mod:`client`：Java 编排域 gRPC 客户端（读版本与灰度规则）；
- :mod:`router`：会话稳定哈希分桶 → 稳定版/灰度版版本号；
- :mod:`expression`：分支表达式白名单求值（不使用 ``eval``）；
- :mod:`builder`：图定义 → LangGraph 图编译；
- :mod:`cache`：TTL + LRU 缓存；
- :mod:`loader`：快照/定义/已编译图的分层缓存装配；
- :mod:`run`：流式执行入口（不可用时回落内置图）。
"""

from app.agent.workflow.run import stream_workflow_or_default

__all__ = ["stream_workflow_or_default"]
