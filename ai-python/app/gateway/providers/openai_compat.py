"""OpenAI 兼容协议 Provider 基类：DeepSeek / 通义千问 / GLM / Kimi 共用。

各家云端模型均提供 OpenAI 兼容的 ``/chat/completions`` 接口，差异仅在于
``base_url`` / ``model`` / 鉴权头，故抽取为统一基类，各 Provider 只注入自身配置
（见 :mod:`app.gateway.providers`）。这样新增模型接入时无需重复实现超时、SSE
解析与错误处理逻辑。
"""

import json
import logging
from collections.abc import AsyncIterator
from typing import Any

import httpx

from app.analytics import begin_llm_call, record_usage
from app.gateway.providers.base import BaseProvider, ProviderStreamEvent, ToolCallRequest

logger = logging.getLogger(__name__)

# SSE 数据行前缀（OpenAI 兼容协议：`data: {...}`）
_SSE_DATA_PREFIX = "data:"
# 流结束标记
_SSE_DONE = "[DONE]"
# usage 帧的廉价探测键（先子串判断、命中才解析 JSON，避免逐行解析开销）
_USAGE_KEY = '"usage"'
# 模型以工具调用结束本轮的 finish_reason
_FINISH_TOOL_CALLS = "tool_calls"
# stream_events 签名不含超时入参，沿用 stream_chat 的默认值（与既有语义一致）
_DEFAULT_TIMEOUT = 30.0
_DEFAULT_FIRST_TOKEN_TIMEOUT = 10.0


class _StreamEnd:
    """SSE ``[DONE]`` 流结束哨兵（与「本行无内容」的 ``None`` 区分）。"""


_STREAM_END = _StreamEnd()


class _ToolCallAccumulator:
    """按 ``index`` 累积单个工具调用的增量分片。

    OpenAI 兼容协议下，``tool_calls`` 是**增量分片**：首个 chunk 通常携带 ``id`` 与
    ``function.name``，后续 chunk 只逐段下发 ``function.arguments``，且多个工具调用
    会以 ``index`` 交错出现。故按 index 分别缓存并按到达顺序拼接。
    """

    __slots__ = ("argument_parts", "id", "name")

    def __init__(self) -> None:
        self.id = ""
        self.name = ""
        self.argument_parts: list[str] = []

    def merge(self, fragment: dict) -> None:
        # id 稳定且只下发一次：以首次出现为准，避免重复分片污染标识
        if fragment.get("id") and not self.id:
            self.id = fragment["id"]
        function = fragment.get("function")
        if not isinstance(function, dict):
            return
        # name / arguments 允许跨 chunk 拆分，统一用拼接兼容两种下发方式
        if function.get("name"):
            self.name += function["name"]
        arguments = function.get("arguments")
        if arguments:
            self.argument_parts.append(arguments)

    def build(self) -> ToolCallRequest:
        return ToolCallRequest(id=self.id, name=self.name, arguments="".join(self.argument_parts))


class OpenAICompatibleProvider(BaseProvider):
    """OpenAI 兼容协议 Provider 通用实现。

    子类只需在构造时注入 ``name`` / ``api_key`` / ``base_url`` / ``model``；未配置
    ``api_key`` 时 :attr:`configured` 为 ``False``，由网关工厂跳过、不纳入降级链。
    """

    def __init__(
        self, name: str, api_key: str, base_url: str, model: str, *, transport: Any = None
    ) -> None:
        self.name = name
        self._api_key = api_key
        # 去掉末尾斜杠，统一以 f"{base_url}/chat/completions" 拼接
        self._base_url = base_url.rstrip("/")
        self._default_model = model
        self._transport = transport  # 仅测试注入（httpx.MockTransport），生产为 None
        # M5-5.1：该 Provider 是否已被证实不支持 stream_options.include_usage
        # （真实踩到 400 后置位，后续不再携带该字段，避免每轮都失败重试）
        self._stream_usage_unsupported = False

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
        payload: dict = {
            "model": model or self._default_model,
            "messages": [{"role": "user", "content": prompt}],
            "stream": stream,
        }
        if stream and not self._stream_usage_unsupported:
            # M5-5.1：要求流式响应在末帧携带 usage，供 Token 消耗报表采集
            payload["stream_options"] = {"include_usage": True}
        return payload

    async def chat(self, prompt: str, model: str | None = None, timeout: float = 30.0) -> str:
        # M5-5.1：记录本次调用的提供方与模型（分析埋点归属）
        begin_llm_call(self.name, model or self._default_model)
        url = f"{self._base_url}/chat/completions"
        async with httpx.AsyncClient(timeout=timeout, transport=self._transport) as client:
            resp = await client.post(url, headers=self._headers(), json=self._payload(prompt, model, False))
            resp.raise_for_status()
            data = resp.json()
        usage = data.get("usage")
        self._log_usage(usage)
        record_usage(usage)
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

        M5-5.1：请求携带 ``stream_options.include_usage`` 以取回真实 Token 用量；
        若该 Provider 因该字段返回 400，则**记住并去掉该字段重试一次**（此后不再携带）。
        """
        begin_llm_call(self.name, model or self._default_model)
        url = f"{self._base_url}/chat/completions"
        payload = self._payload(prompt, model, True)
        wants_usage = "stream_options" in payload
        try:
            async with (
                self._stream_client(timeout, first_token_timeout) as client,
                client.stream("POST", url, headers=self._headers(stream=True), json=payload) as resp,
            ):
                resp.raise_for_status()
                async for line in resp.aiter_lines():
                    self._capture_usage(line)
                    delta = self._parse_sse_line(line)
                    if delta is _STREAM_END:
                        return
                    if delta:
                        yield delta
        except httpx.HTTPStatusError as exc:
            if not wants_usage or exc.response.status_code != 400:
                raise
            # 兼容降级：该 Provider 不支持 stream_options → 记住并重试一次（此时 payload 不带该字段）
            self._stream_usage_unsupported = True
            logger.warning(
                "Provider %s 不支持 stream_options.include_usage（HTTP 400），已禁用该字段并重试一次",
                self.name,
            )
            async for delta in self.stream_chat(prompt, model, timeout, first_token_timeout):
                yield delta

    async def stream_events(
        self,
        messages: list[dict],
        tools: list[dict] | None = None,
        model: str | None = None,
    ) -> AsyncIterator[ProviderStreamEvent]:
        """原生流式事件：解析文本增量与 ``tool_calls`` 分片，支持 Function Calling。

        ``tools`` 非空时在请求体携带工具定义；SSE 中的 ``delta.content`` 产出文本
        事件，``delta.tool_calls`` **按 index 增量累积**，在 ``finish_reason ==
        "tool_calls"`` 或流结束时整体产出 ``tool_call`` 事件；``[DONE]`` / 流结束
        产出终止事件。非法行沿用宽松解析，跳过不中断。

        M5-5.1：与 ``stream_chat`` 一致携带 ``stream_options.include_usage`` 采 Token 用量；
        若该 Provider 因该字段返回 400，则记住并去掉该字段重试一次。
        """
        begin_llm_call(self.name, model or self._default_model)
        url = f"{self._base_url}/chat/completions"
        payload = self._events_payload(messages, model, tools)
        wants_usage = "stream_options" in payload
        pending: dict[int, _ToolCallAccumulator] = {}
        try:
            async with (
                self._stream_client(_DEFAULT_TIMEOUT, _DEFAULT_FIRST_TOKEN_TIMEOUT) as client,
                client.stream("POST", url, headers=self._headers(stream=True), json=payload) as resp,
            ):
                resp.raise_for_status()
                async for line in resp.aiter_lines():
                    self._capture_usage(line)
                    data = self._sse_data(line)
                    if data is None:
                        continue
                    if data == _SSE_DONE:
                        break
                    choice = self._first_choice(data)
                    if choice is None:
                        continue
                    delta = choice.get("delta")
                    if isinstance(delta, dict):
                        content = delta.get("content")
                        if content:
                            yield ProviderStreamEvent(delta=content)
                        self._merge_tool_calls(pending, delta.get("tool_calls"))
                    if choice.get("finish_reason") == _FINISH_TOOL_CALLS:
                        for call in self._drain_tool_calls(pending):
                            yield ProviderStreamEvent(tool_call=call)
        except httpx.HTTPStatusError as exc:
            if not wants_usage or exc.response.status_code != 400:
                raise
            self._stream_usage_unsupported = True
            logger.warning(
                "Provider %s 不支持 stream_options.include_usage（HTTP 400），已禁用该字段并重试一次",
                self.name,
            )
            async for event in self.stream_events(messages, tools=tools, model=model):
                yield event
            return
        # 流自然结束（未收到 finish_reason 或 [DONE]）：补齐残留工具调用后终止
        for call in self._drain_tool_calls(pending):
            yield ProviderStreamEvent(tool_call=call)
        yield ProviderStreamEvent()

    def _events_payload(self, messages: list[dict], model: str | None, tools: list[dict] | None) -> dict:
        payload: dict = {
            "model": model or self._default_model,
            "messages": messages,
            "stream": True,
        }
        if tools:
            payload["tools"] = tools
        if not self._stream_usage_unsupported:
            # M5-5.1：工具链路同样要求 usage 帧（工具多轮各计一次调用）
            payload["stream_options"] = {"include_usage": True}
        return payload

    def _capture_usage(self, line: str) -> None:
        """SSE 旁路：识别 usage 帧并上报分析埋点（不改动 delta 解析）。

        OpenAI 兼容协议在 ``stream_options.include_usage`` 下会于 ``[DONE]`` 前下发一帧
        ``choices`` 为空、仅含 ``usage`` 的 chunk。先做廉价的子串判断再解析 JSON，
        避免对每一行都做一次完整 JSON 解析（流式行数多，代价不可忽略）。
        """
        if _USAGE_KEY not in line:
            return
        raw = self._sse_data(line)
        if raw is None:
            return
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError:
            logger.debug("usage 帧解析失败，已跳过: %.80s", line)
            return
        if isinstance(obj, dict) and isinstance(obj.get("usage"), dict):
            record_usage(obj["usage"])

    def _stream_client(self, timeout: float, first_token_timeout: float) -> httpx.AsyncClient:
        """构造带超时策略的流式客户端（``transport`` 可注入，便于离线测试）。

        超时策略：``connect`` 用 ``first_token_timeout`` 兜住首字前建连；``read`` 用
        ``timeout`` 兜住整体与片段间隔。
        """
        timeout_config = httpx.Timeout(
            connect=first_token_timeout, read=timeout, write=timeout, pool=first_token_timeout
        )
        return httpx.AsyncClient(timeout=timeout_config, transport=self._transport)

    @staticmethod
    def _merge_tool_calls(pending: dict[int, _ToolCallAccumulator], fragments: object) -> None:
        """把一批 ``delta.tool_calls`` 分片合并进对应的 index 累积器。"""
        if not isinstance(fragments, list):
            return
        for fragment in fragments:
            if not isinstance(fragment, dict):
                continue
            index = fragment.get("index", 0)
            if not isinstance(index, int):
                continue
            accumulator = pending.get(index)
            if accumulator is None:
                accumulator = _ToolCallAccumulator()
                pending[index] = accumulator
            accumulator.merge(fragment)

    @staticmethod
    def _drain_tool_calls(pending: dict[int, _ToolCallAccumulator]) -> list[ToolCallRequest]:
        """按 index 顺序产出并清空累积完成的工具调用。"""
        calls = [pending[index].build() for index in sorted(pending)]
        pending.clear()
        return calls

    @staticmethod
    def _sse_data(line: str) -> str | None:
        """剥离 ``data:`` 前缀返回 JSON 文本；非数据行返回 ``None``。"""
        stripped = line.strip()
        if not stripped.startswith(_SSE_DATA_PREFIX):
            return None
        return stripped[len(_SSE_DATA_PREFIX) :].strip()

    @staticmethod
    def _first_choice(data: str) -> dict | None:
        """解析 SSE 数据行并取首个 choice；非法行或空 choices 返回 ``None``。"""
        try:
            obj = json.loads(data)
        except json.JSONDecodeError:
            logger.debug("跳过非法 SSE 数据行: %s", data[:80])
            return None
        if not isinstance(obj, dict):
            return None
        choices = obj.get("choices") or []
        if not choices or not isinstance(choices[0], dict):
            return None
        return choices[0]

    @staticmethod
    def _parse_sse_line(line: str) -> str | None | _StreamEnd:
        """解析单行 SSE 的文本内容（``chat`` 通道沿用）。

        Returns:
            文本片段；无内容返回 ``None``；遇 ``[DONE]`` 返回 :data:`_STREAM_END`。
        """
        data = OpenAICompatibleProvider._sse_data(line)
        if data is None:
            return None
        if data == _SSE_DONE:
            return _STREAM_END
        choice = OpenAICompatibleProvider._first_choice(data)
        if choice is None:
            return None
        delta = choice.get("delta")
        content = delta.get("content") if isinstance(delta, dict) else None
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
