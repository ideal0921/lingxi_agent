"""DeepSeek Provider：基于 OpenAI 兼容协议的非流式调用。"""

import httpx

from app.config import settings
from app.gateway.providers.base import BaseProvider


class DeepSeekProvider(BaseProvider):
    """DeepSeek 模型接入（M1 主模型）。"""

    name = "deepseek"

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        url = f"{settings.deepseek_base_url}/chat/completions"
        headers = {"Authorization": f"Bearer {settings.deepseek_api_key}"}
        payload = {
            "model": model or settings.deepseek_model,
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
        }
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(url, headers=headers, json=payload)
            resp.raise_for_status()
            data = resp.json()
        return data["choices"][0]["message"]["content"]
