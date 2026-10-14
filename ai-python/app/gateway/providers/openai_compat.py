"""OpenAI 兼容协议 Provider 基类：DeepSeek / 通义千问 / GLM / Kimi 共用。

各家云端模型均提供 OpenAI 兼容的 ``/chat/completions`` 接口，差异仅在于
``base_url`` / ``model`` / 鉴权头，故抽取为统一基类，各 Provider 只注入自身配置
（见 :mod:`app.gateway.providers`）。这样新增模型接入时无需重复实现超时、SSE
解析与错误处理逻辑。
"""

import json
import logging
from collections.abc import AsyncIterator

import httpx

from app.gateway.providers.base import BaseProvider

logger = logging.getLogger(__name__)

# SSE 数据行前缀（OpenAI 兼容协议：`data: {...}`）
_SSE_DATA_PREFIX = "data:"
# 流结束标记
_SSE_DONE = "[DONE]"


class _StreamEnd:
    """SSE ``[DONE]`` 流结束哨兵（与「本行无内容」的 ``None`` 区分）。"""


_STREAM_END = _StreamEnd()


class OpenAICompatibleProvider(BaseProvider):
    """OpenAI 兼容协议 Provider 通用实现。

    子类只需在构造时注入 ``name`` / ``api_key`` / ``base_url`` / ``model``；未配置
    ``api_key`` 时 :attr:`configured` 为 ``False``，由网关工厂跳过、不纳入降级链。
    """

    def __init__(self, name: str, api_key: str, base_url: str, model: str) -> None:
        self.name = name
        self._api_key = api_key
        # 去掉末尾斜杠，统一以 f"{base_url}/chat/completions" 拼接
        self._base_url = base_url.rstrip("/")
        self._default_model = model

    @property
    def configured(self) -> bool:
        """是否已配置密钥；未配置者不参与降级链。"""
        return bool(self._api_key)

    @property
    def base_url(self) -> str:
        """接入地址（只读，便于观测与单测断言）。"""
        return self._base_url

    @property
    def default_model(self) -> str:
        """默认模型名（只读）。"""
        return self._default_model

    def _headers(self, stream: bool = False) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {self._api_key}"}
        if stream:
            headers["Accept"] = "text/event-stream"
        return headers

    def _payload(self, prompt: str, model: str | None, stream: bool) -> dict:
        return {
            "model": model or self._default_model,
            "messages": [{"role": "user", "content": prompt}],
            "stream": stream,
        }

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        url = f"{self._base_url}/chat/completions"
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(url, headers=self._headers(), json=self._payload(prompt, model, False))
            resp.raise_for_status()
            data = resp.json()
        self._log_usage(data.get("usage"))
        return data["choices"][0]["message"]["content"]

    async def stream_chat(
        self,
        prompt: str,
        model: str | None = None,
        timeout: float = 30.0,
        first_token_timeout: float = 10.0,
    ) -> AsyncIterator[str]:
        """SSE 流式调用：逐 `data:` 行解析增量片段（``choices[0].delta.content``）。

        超时策略：``connect`` 用 ``first_token_timeout`` 兜住首字前建连；``read`` 用
        ``timeout`` 兜住整体与片段间隔。解析采用宽松模式——非法行跳过不中断，
        保证弱网下流不因脏数据整体失败。
        """
        url = f"{self._base_url}/chat/completions"
        payload = self._payload(prompt, model, True)
        read_timeout = httpx.Timeout(
            connect=first_token_timeout, read=timeout, write=timeout, pool=first_token_timeout
        )
        async with (
            httpx.AsyncClient(timeout=read_timeout) as client,
            client.stream("POST", url, headers=self._headers(stream=True), json=payload) as resp,
        ):
            resp.raise_for_status()
            async for line in resp.aiter_lines():
                delta = self._parse_sse_line(line)
                if delta is _STREAM_END:
                    return
                if delta:
                    yield delta

    @staticmethod
    def _parse_sse_line(line: str) -> str | None | _StreamEnd:
        """解析单行 SSE。

        Returns:
            文本片段；无内容返回 ``None``；遇 ``[DONE]`` 返回 :data:`_STREAM_END`。
        """
        stripped = line.strip()
        if not stripped.startswith(_SSE_DATA_PREFIX):
            return None
        data = stripped[len(_SSE_DATA_PREFIX) :].strip()
        if data == _SSE_DONE:
            return _STREAM_END
        try:
            obj = json.loads(data)
        except json.JSONDecodeError:
            logger.debug("跳过非法 SSE 数据行: %s", data[:80])
            return None
        choices = obj.get("choices") or []
        if not choices:
            return None
        delta = choices[0].get("delta") or {}
        content = delta.get("content")
        return content if content else None

    def _log_usage(self, usage: dict | None) -> None:
        """Token 审计：非流式响应携带用量时记录，便于成本核算与容量观测。"""
        if not usage:
            return
        logger.info(
            "LLM provider=%s token 用量 prompt=%s completion=%s total=%s",
            self.name,
            usage.get("prompt_tokens"),
            usage.get("completion_tokens"),
            usage.get("total_tokens"),
        )
