"""FastAPI 应用入口：健康检查 + LLM 网关最小验证接口。"""

from fastapi import FastAPI
from pydantic import BaseModel

from app.config import settings
from app.gateway.router import gateway

app = FastAPI(title=settings.app_name, version="1.0.0")


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
