"""FastAPI 应用入口：健康检查 + LLM 网关验证 + Agent 对话接口 + Kafka 消费协程。

生命周期（lifespan）：
- 启动：``LINGXI_KAFKA_ENABLED=true`` 时拉起会话消息消费协程（流式全链路入口）；
- 停机：取消消费协程并释放运行时资源（Kafka 生产者）。
"""

import asyncio
import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel, Field

from app.agent.state import ChatMessage
from app.config import settings
from app.gateway.router import gateway
from app.runtime import get_agent, shutdown

logger = logging.getLogger(__name__)

# 服务入口统一日志配置：uvicorn 默认只配置自身 logger，应用日志需显式开启
# （否则 INFO 级日志不可见，异常排查困难）。
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
)


def _log_task_failure(task: asyncio.Task[None]) -> None:
    """后台任务异常必须显式记录：异步任务异常若不取回会被 asyncio 静默吞掉。"""
    if task.cancelled():
        return
    error = task.exception()
    if error is not None:
        logger.error("后台任务异常退出", exc_info=error)


async def _run_streaming_consumer() -> None:
    """拉起会话消息消费协程（导入置于函数内，避免未启用 Kafka 时也加载 aiokafka）。"""
    from app.consumers.session_message import run_session_message_consumer
    from app.runtime import get_producer, get_retriever

    await run_session_message_consumer(
        config=settings,
        producer=await get_producer(),
        retriever=await get_retriever(),
        gateway=gateway,
    )


async def _run_kb_ingest_consumer() -> None:
    """拉起知识库入库消费协程（M3-3.2：kb.doc.ingest → 入库 → kb.doc.indexed）。"""
    from app.consumers.kb_ingest import run_kb_ingest_consumer
    from app.runtime import get_embedder, get_producer, get_store

    await run_kb_ingest_consumer(
        config=settings,
        producer=await get_producer(),
        store=await get_store(),
        embedder=await get_embedder(),
    )


async def _run_kb_delete_consumer() -> None:
    """拉起知识库下线清理协程（M3-3.2：kb.doc.delete → 删除向量库切片）。"""
    from app.consumers.kb_ingest import run_kb_delete_consumer
    from app.runtime import get_store

    await run_kb_delete_consumer(config=settings, store=await get_store())


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncGenerator[None]:
    consumer_tasks: list[asyncio.Task[None]] = []
    if settings.kafka_enabled:
        runners = (
            ("会话消息", _run_streaming_consumer),
            ("知识库入库", _run_kb_ingest_consumer),
            ("知识库下线清理", _run_kb_delete_consumer),
        )
        for name, runner in runners:
            task = asyncio.create_task(runner())
            task.add_done_callback(_log_task_failure)
            consumer_tasks.append(task)
            logger.info("流式消费协程已启动: %s", name)
    yield
    for task in consumer_tasks:
        task.cancel()
    for task in consumer_tasks:
        try:
            await task
        except asyncio.CancelledError:
            pass
    await shutdown()


app = FastAPI(title=settings.app_name, version="1.0.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    """健康检查，供 LB / K8s 探活。"""
    return {"status": "ok", "service": "ai-python"}


class GenerateRequest(BaseModel):
    """非流式生成请求（M1 验证 LLM 网关链路）。"""

    prompt: str
    model: str | None = None


@app.post("/api/v1/generate")
async def generate(req: GenerateRequest) -> dict:
    """调用 LLM 网关生成回答，验证超时/重试/降级框架。"""
    answer = await gateway.generate(req.prompt, req.model)
    return {"answer": answer}


class ChatMessageIn(BaseModel):
    """对话历史条目（入参）。"""

    role: str
    content: str


class AgentChatRequest(BaseModel):
    """Agent 非流式对话请求（M3-3.7：加载上下文 → 检索 → 生成）。"""

    query: str
    tenant_id: str
    kb_id: str | None = None
    session_id: str = ""
    history: list[ChatMessageIn] = Field(default_factory=list)


@app.post("/api/v1/agent/chat")
async def agent_chat(req: AgentChatRequest) -> dict:
    """非流式 Agent 对话：知识检索 → 精排 → 生成，返回回答与引用来源。"""
    agent = await get_agent()
    state = await agent.ainvoke(
        {
            "query": req.query,
            "tenant_id": req.tenant_id,
            "kb_id": req.kb_id,
            "session_id": req.session_id,
            "history": [ChatMessage(role=item.role, content=item.content) for item in req.history],
        }
    )
    contexts = state.get("contexts") or []
    return {
        "answer": state.get("answer", ""),
        "references": [
            {"heading_path": hit.heading_path, "doc_id": hit.doc_id, "score": hit.score}
            for hit in contexts
        ],
    }


async def _run_eval_task() -> None:
    """后台执行一次 Golden Set 回归（M3-3.10；导入置于函数内，避免启动即加载评测依赖）。"""
    from app.evaluator.runner import launch_eval
    from app.runtime import get_embedder, get_retriever, get_store

    await launch_eval(
        retriever=await get_retriever(),
        gateway=gateway,
        store=await get_store(),
        embedder=await get_embedder(),
        config=settings,
    )


@app.post("/api/v1/eval/run")
async def eval_run() -> dict:
    """触发一次 Golden Set 回归（后台执行，避免长耗时占用请求）。"""
    from app.evaluator.runner import is_running

    if is_running():
        return {"status": "running", "message": "已有评测在执行，请稍后再试或查询报告"}
    task = asyncio.create_task(_run_eval_task())
    task.add_done_callback(_log_task_failure)
    return {"status": "started", "message": "评测已启动，结果通过 GET /api/v1/eval/report 查询"}


@app.get("/api/v1/eval/report")
async def eval_report() -> dict:
    """查询最近一次评测摘要（进程内缓存；未跑过时返回 status=empty）。"""
    from app.evaluator.runner import is_running, latest_summary

    return {**latest_summary(), "running": is_running()}
