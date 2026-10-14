"""Embedding 抽象与实现：把文本转为稠密向量 + 稀疏词权重。

五种实现（经 :func:`create_embedder` 按配置装配）：
- :class:`HybridEmbedder`（**生产默认，方案 C**）：稠密取云端
  （OpenAI 兼容 ``/v1/embeddings``，默认国内低延迟服务，免本机部署、快），
  稀疏取本地 :class:`KeywordSparseEmbedder`（jieba + BM25 风格 TF，纯 CPU
  毫秒级）——保留 Milvus 双字段与混合检索。
- :class:`CloudDenseEmbedder`：云端稠密嵌入（Provider 无关的 OpenAI 兼容协议），
  **只输出稠密向量**（云端不返回 bge-m3 的稀疏词权重）。
- :class:`CachedEmbedder`：查询向量缓存（LRU + TTL），**M3-3.8 首字延迟优化**——
  命中即跳过云端网络往返（实测检索阶段占首字延迟 82~88%）。
- :class:`BgeM3Embedder`：本机自部署 FlagEmbedding，稠密 + 稀疏**原生完整**
  （需 CPU/GPU 算力，模型约 2.2GB）。
- :class:`HashEmbedder`：确定性伪向量，**仅供离线开发与单测**，不可用于生产。

设计说明：:class:`Embedder` 协议只暴露 ``dim`` 与 ``embed(texts)``，使入库管道与
具体模型解耦，便于替换实现、加缓存或 Mock。
"""

from __future__ import annotations

import hashlib
import logging
import math
import re
import time
from collections import Counter, OrderedDict
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Protocol

from app.config import Settings

logger = logging.getLogger(__name__)

# 稀疏向量索引上界（Milvus 要求 uint32）
_SPARSE_INDEX_MOD = 2**31 - 1
_NGRAM_SIZE = 2


class EmbedderDependencyError(RuntimeError):
    """Embedding 依赖或模型缺失。"""


@dataclass(slots=True)
class EmbedResult:
    """向量化结果：稠密向量与稀疏词权重一一对应传入文本。"""

    dense: list[list[float]]
    sparse: list[dict[int, float]]


@dataclass(slots=True)
class _CacheEntry:
    """查询向量缓存条目：向量与过期时刻。"""

    dense: list[float]
    sparse: dict[int, float]
    expires_at: float


class Embedder(Protocol):
    """Embedding 统一协议。"""

    @property
    def dim(self) -> int:
        """稠密向量维度。"""
        ...

    def embed(self, texts: Sequence[str]) -> EmbedResult:
        """批量向量化文本。"""
        ...


class BgeM3Embedder:
    """bge-m3 语义嵌入（稠密 1024 维 + 稀疏词权重），生产实现。"""

    def __init__(self, model_name: str = "BAAI/bge-m3", batch_size: int = 16) -> None:
        try:
            from FlagEmbedding import BGEM3FlagModel
        except ImportError as exc:
            raise EmbedderDependencyError(
                "使用 bge-m3 需安装 FlagEmbedding，请执行 pip install FlagEmbedding"
            ) from exc
        self._model = BGEM3FlagModel(model_name, use_fp16=True)
        self._batch_size = batch_size
        self._dim = 1024

    @property
    def dim(self) -> int:
        return self._dim

    def embed(self, texts: Sequence[str]) -> EmbedResult:
        if not texts:
            return EmbedResult(dense=[], sparse=[])
        output = self._model.encode(
            list(texts), batch_size=self._batch_size, return_dense=True, return_sparse=True
        )
        dense = [vector.tolist() for vector in output["dense_vecs"]]
        # lexical_weights 为 {token_id: weight}，可直接作为 Milvus 稀疏向量
        sparse = [
            {int(token): float(weight) for token, weight in weights.items()}
            for weights in output["lexical_weights"]
        ]
        return EmbedResult(dense=dense, sparse=sparse)


class HashEmbedder:
    """确定性伪向量（字符 bigram 哈希袋），仅用于离线开发与单测。"""

    def __init__(self, dim: int = 1024) -> None:
        self._dim = dim

    @property
    def dim(self) -> int:
        return self._dim

    def embed(self, texts: Sequence[str]) -> EmbedResult:
        dense: list[list[float]] = []
        sparse: list[dict[int, float]] = []
        for text in texts:
            grams = _bigrams(text)
            vector = [0.0] * self._dim
            weights: dict[int, float] = {}
            for gram in grams:
                vector[_hash_index(gram, self._dim)] += 1.0
                weights[_hash_index(gram, _SPARSE_INDEX_MOD)] = weights.get(
                    _hash_index(gram, _SPARSE_INDEX_MOD), 0.0
                ) + 1.0
            dense.append(_l2_normalize(vector))
            sparse.append(_normalize_weights(weights))
        return EmbedResult(dense=dense, sparse=sparse)


class CloudDenseEmbedder:
    """云端稠密嵌入（OpenAI 兼容 ``/v1/embeddings``，Provider 无关）。

    默认指向**国内低延迟服务**（硅基流动 ``BAAI/bge-m3``），亦可仅改 base_url/model
    切回 OpenRouter 等境外服务——两者协议一致，代码无需改动（M3-3.8 首字延迟优化）。

    **注意**：云端接口只返回稠密向量，不返回 bge-m3 的稀疏词权重
    （lexical_weights），故本实现的 ``sparse`` 恒为空字典；稀疏侧由
    :class:`KeywordSparseEmbedder` 本地生成，两者经 :class:`HybridEmbedder`
    组合后写入 Milvus 的两个向量字段。

    调用约束（对齐架构强制项）：批量请求、超时控制、指数退避重试、
    返回维度校验。
    """

    def __init__(
        self,
        api_key: str,
        *,
        base_url: str = "https://api.siliconflow.cn/v1",
        model: str = "BAAI/bge-m3",
        dim: int = 1024,
        batch_size: int = 16,
        timeout: float = 30.0,
        max_retries: int = 2,
        transport: Any = None,
    ) -> None:
        if not api_key:
            raise EmbedderDependencyError(
                "使用云端稠密嵌入需配置环境变量 LINGXI_DENSE_EMBED_API_KEY"
            )
        self._api_key = api_key
        self._endpoint = f"{base_url.rstrip('/')}/embeddings"
        self._model = model
        self._dim = dim
        self._batch_size = max(1, batch_size)
        self._timeout = timeout
        self._max_retries = max_retries
        self._transport = transport  # 仅测试注入（httpx.MockTransport），生产为 None
        # 长连接客户端：复用 TCP/TLS 连接，避免每次调用重做握手（首字延迟优化）
        self._client: Any = None

    @property
    def dim(self) -> int:
        return self._dim

    def _get_client(self) -> Any:
        """惰性创建并复用 httpx.Client（进程内长连接）。"""
        if self._client is None:
            import httpx

            self._client = httpx.Client(timeout=self._timeout, transport=self._transport)
        return self._client

    def close(self) -> None:
        """释放长连接（进程停机或测试清理时调用）。"""
        if self._client is not None:
            self._client.close()
            self._client = None

    def embed(self, texts: Sequence[str]) -> EmbedResult:
        if not texts:
            return EmbedResult(dense=[], sparse=[])
        dense: list[list[float]] = []
        items = list(texts)
        for start in range(0, len(items), self._batch_size):
            dense.extend(self._request_batch(items[start : start + self._batch_size]))
        # 云端无稀疏词权重，返回空字典占位（由本地稀疏侧补齐）
        return EmbedResult(dense=dense, sparse=[{} for _ in items])

    def _request_batch(self, batch: Sequence[str]) -> list[list[float]]:
        """单批请求：指数退避重试 + 维度校验（复用长连接）。"""
        payload = {"model": self._model, "input": list(batch)}
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        last_error: Exception | None = None
        for attempt in range(self._max_retries + 1):
            if attempt > 0:
                time.sleep(2**attempt * 0.2)  # 指数退避 0.4s / 0.8s ...
            try:
                response = self._get_client().post(self._endpoint, json=payload, headers=headers)
                response.raise_for_status()
                data = response.json()
                vectors = [item["embedding"] for item in data["data"]]
                self._validate(vectors)
                return vectors
            except Exception as exc:  # noqa: BLE001 - 重试后仍失败才抛出
                last_error = exc
                logger.warning("云端稠密嵌入请求失败(第%d次): %s", attempt + 1, exc)
        raise EmbedderDependencyError(f"云端稠密嵌入调用失败: {last_error}") from last_error

    def _validate(self, vectors: list[list[float]]) -> None:
        """校验返回维度，防止模型切换导致向量维度与 Milvus schema 不符。"""
        for vector in vectors:
            if len(vector) != self._dim:
                raise EmbedderDependencyError(
                    f"嵌入维度不符：期望 {self._dim}，实际 {len(vector)}"
                )


class KeywordSparseEmbedder:
    """本地稀疏词权重（jieba 分词 + BM25 风格 TF 饱和），纯 CPU 毫秒级。

    **仅用于 :class:`HybridEmbedder` 的稀疏侧**：``dense`` 恒为空列表（若被
    误直接传给入库管道，会因数量不一致而明确报错，不会静默写入错误向量）。

    与 bge-m3 原生稀疏的差异：原生为**学习式**权重（可对语义相关词给分），
    此处为**词频式**权重（纯词汇匹配）；用于关键词召回时效果可接受，RRF 融合
    仍然有效。
    """

    def __init__(self, dim: int = 1024) -> None:
        self._dim = dim

    @property
    def dim(self) -> int:
        return 0  # 无稠密输出；维度由 HybridEmbedder 的稠密侧决定

    def embed(self, texts: Sequence[str]) -> EmbedResult:
        return EmbedResult(dense=[], sparse=[_keyword_weights(text) for text in texts])


class HybridEmbedder:
    """混合嵌入：稠密取自云端，稀疏取自本地（M3-3.4 方案 C）。"""

    def __init__(self, dense: Embedder, sparse: Embedder) -> None:
        self._dense = dense
        self._sparse = sparse

    @property
    def dim(self) -> int:
        return self._dense.dim

    def embed(self, texts: Sequence[str]) -> EmbedResult:
        if not texts:
            return EmbedResult(dense=[], sparse=[])
        dense_result = self._dense.embed(texts)
        sparse_result = self._sparse.embed(texts)
        if len(dense_result.dense) != len(sparse_result.sparse):
            raise EmbedderDependencyError("稠密与稀疏向量数量不一致，混合嵌入失败")
        return EmbedResult(dense=dense_result.dense, sparse=sparse_result.sparse)


def _bigrams(text: str) -> list[str]:
    """切分字符 bigram；单字文本退化为自身，保证非空文本有词。"""
    stripped = "".join(text.split())
    if len(stripped) < _NGRAM_SIZE:
        return [stripped] if stripped else []
    return [stripped[i : i + _NGRAM_SIZE] for i in range(len(stripped) - 1)]


def _hash_index(token: str, mod: int) -> int:
    """对 token 做确定性哈希映射到 [0, mod)。"""
    digest = hashlib.sha256(token.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], "big") % mod


def _l2_normalize(vector: list[float]) -> list[float]:
    """L2 归一化（COSINE 检索要求方向可比）；全零向量原样返回。"""
    norm = sum(value * value for value in vector) ** 0.5
    if norm == 0:
        return vector
    return [value / norm for value in vector]


def _normalize_weights(weights: dict[int, float]) -> dict[int, float]:
    """稀疏权重归一化到最大值为 1，避免文本长度影响内积得分。"""
    if not weights:
        return {}
    maximum = max(weights.values())
    return {index: value / maximum for index, value in weights.items()}


# 中文标点/空白：分词结果中需剔除，避免污染稀疏词表
_PUNCTUATION_PATTERN = re.compile(r"^[\W_]+$", re.UNICODE)


def _keyword_weights(text: str) -> dict[int, float]:
    """jieba 分词 + BM25 风格 TF 饱和权重 → 稀疏向量（``index → weight``）。

    权重 = ``(1 + log(tf)) / sqrt(词数)``，再做最大值归一化：log 饱和抑制高频词
    的线性放大，长度因子降低长文本整体得分；归一化保证内积只反映词汇构成与
    权重分布。
    """
    import jieba  # 惰性导入：首次调用才构建词典（约 1s），避免拖慢服务启动

    tokens = [
        token
        for raw in jieba.lcut(text)
        if (token := raw.strip().lower()) and not _PUNCTUATION_PATTERN.match(token)
    ]
    if not tokens:
        return {}
    counts = Counter(tokens)
    length_factor = math.sqrt(len(tokens))
    scaled = {
        term: (1.0 + math.log(count)) / length_factor for term, count in counts.items()
    }
    return {
        _hash_index(term, _SPARSE_INDEX_MOD): value
        for term, value in _normalize_weights(scaled).items()
    }


class CachedEmbedder:
    """查询向量缓存（M3-3.8 首字延迟优化）。

    客服场景重复/近似问题占比高，而云端稠密嵌入的网络往返是首字延迟的主要来源
    （实测占 82~88%）。命中缓存即可完全跳过一次云端往返，把该阶段压到微秒级。

    实现：**按文本精确匹配**的 LRU + TTL 缓存（进程内、无外部依赖）；批量请求时
    仅对未命中文本调用下游，并按原顺序回填，保证与下游语义一致。

    Args:
        inner: 被包装的嵌入器（查询路径通常为 :class:`HybridEmbedder`）。
        max_size: 缓存条目上限（0 表示禁用缓存）。
        ttl: 条目存活秒数（过期后失效重新计算，避免知识/模型更新后长期命中旧向量）。
    """

    def __init__(self, inner: Embedder, *, max_size: int = 512, ttl: float = 300.0) -> None:
        self._inner = inner
        self._max_size = max(0, max_size)
        self._ttl = ttl
        self._entries: OrderedDict[str, _CacheEntry] = OrderedDict()
        self.hits = 0
        self.misses = 0

    @property
    def dim(self) -> int:
        return self._inner.dim

    def embed(self, texts: Sequence[str]) -> EmbedResult:
        items = list(texts)
        if not items:
            return EmbedResult(dense=[], sparse=[])
        if self._max_size == 0:
            return self._inner.embed(items)

        now = time.monotonic()
        dense: list[list[float]] = [[] for _ in items]
        sparse: list[dict[int, float]] = [{} for _ in items]
        miss_positions: list[int] = []
        miss_texts: list[str] = []

        for position, text in enumerate(items):
            entry = self._entries.get(text)
            if entry is not None and entry.expires_at > now:
                self._entries.move_to_end(text)  # LRU：命中项置为最近使用
                dense[position] = entry.dense
                sparse[position] = entry.sparse
                self.hits += 1
                continue
            if entry is not None:
                self._entries.pop(text, None)  # TTL 过期：丢弃旧条目
            miss_positions.append(position)
            miss_texts.append(text)
            self.misses += 1

        if miss_texts:
            result = self._inner.embed(miss_texts)
            for offset, position in enumerate(miss_positions):
                dense[position] = result.dense[offset]
                sparse[position] = result.sparse[offset]
                self._remember(miss_texts[offset], result.dense[offset], result.sparse[offset], now)
        return EmbedResult(dense=dense, sparse=sparse)

    def _remember(
        self, text: str, dense: list[float], sparse: dict[int, float], now: float
    ) -> None:
        """写入缓存并淘汰最久未使用项。"""
        self._entries[text] = _CacheEntry(dense=dense, sparse=sparse, expires_at=now + self._ttl)
        self._entries.move_to_end(text)
        while len(self._entries) > self._max_size:
            self._entries.popitem(last=False)


def create_embedder(config: Settings) -> Embedder:
    """按配置装配 Embedder。

    Args:
        config: 应用配置（读取 ``embedder_provider`` 等字段）。

    Returns:
        对应实现；``cloud-hybrid`` 为生产默认（云端稠密 + 本地稀疏）。

    Raises:
        ValueError: 未知 provider。
        EmbedderDependencyError: 所选通道的依赖或凭证缺失。
    """
    provider = config.embedder_provider
    if provider == "hash":
        return HashEmbedder(dim=config.embed_dim)
    if provider == "bge-m3":
        return BgeM3Embedder(batch_size=config.embed_batch_size)
    if provider == "cloud-hybrid":
        dense = CloudDenseEmbedder(
            config.dense_embed_api_key,
            base_url=config.dense_embed_base_url,
            model=config.dense_embed_model,
            dim=config.embed_dim,
            batch_size=config.embed_batch_size,
            timeout=config.dense_embed_timeout,
            max_retries=config.dense_embed_max_retries,
        )
        return HybridEmbedder(dense=dense, sparse=KeywordSparseEmbedder(dim=config.embed_dim))
    raise ValueError(f"未知的 embedder provider: {provider}")
