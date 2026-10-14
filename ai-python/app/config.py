"""应用配置：全部环境变量化，前缀 LINGXI_，无硬编码密钥。

配置来源与优先级：**系统环境变量 > ``ai-python/.env`` > 代码默认值**。
``.env`` 仅用于本地开发（已被 .gitignore 忽略）；容器化部署直接注入环境变量。
"""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# 显式指向工程根下的 .env：避免相对路径随 CWD 变化而失效
_ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


class Settings(BaseSettings):
    """运行配置，支持 .env 文件与系统环境变量。"""

    model_config = SettingsConfigDict(env_prefix="LINGXI_", env_file=_ENV_FILE, extra="ignore")

    # 服务信息
    app_name: str = "ai-python"

    # LLM 网关（DeepSeek 主模型，qwen/glm/kimi 为备用降级链）
    # 密钥等敏感值一律来自 .env 或系统环境变量，禁止硬编码进代码
    # 降级链：逗号分隔的 Provider 名，按顺序降级；未配置密钥的 Provider 自动跳过
    llm_provider_chain: str = "deepseek,qwen,glm,kimi"

    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"

    # 通义千问（阿里云 DashScope OpenAI 兼容模式）
    qwen_api_key: str = ""
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"

    # 智谱 GLM-4（OpenAI 兼容协议）
    glm_api_key: str = ""
    glm_base_url: str = "https://open.bigmodel.cn/api/paas/v4"
    glm_model: str = "glm-4-plus"

    # Kimi（Moonshot，OpenAI 兼容协议）
    kimi_api_key: str = ""
    kimi_base_url: str = "https://api.moonshot.cn/v1"
    kimi_model: str = "moonshot-v1-8k"

    # 调用约束：超时 / 重试 / 降级（首字 10s / 总 120s，对齐架构指标）
    llm_timeout: float = 120.0
    llm_first_token_timeout: float = 10.0
    llm_max_retries: int = 2
    # 指数退避基数：第 n 轮重试等待 base * 2^(n-1) 秒
    llm_retry_backoff_base: float = 0.5

    # 知识库：Milvus 向量检索（M3-3.5）
    milvus_uri: str = "http://127.0.0.1:19530"
    milvus_chunk_collection: str = "lingxi_kb_chunk"  # 子块集合别名
    milvus_collection_version: str = "v1"  # 物理集合版本后缀，升级时递增

    # Embedding 通道（M3-3.4 方案 C：云端稠密 + 本地稀疏）
    embedder_provider: str = "cloud-hybrid"  # cloud-hybrid | bge-m3 | hash
    embed_dim: int = 1024  # bge-m3 稠密向量维度
    embed_batch_size: int = 16
    # 查询向量缓存（M3-3.8 首字延迟优化）：命中即跳过云端网络往返（延迟主因）
    embed_cache_size: int = 512
    embed_cache_ttl: float = 300.0

    # 云端稠密嵌入（OpenAI 兼容 /v1/embeddings）
    # 默认指向**国内低延迟服务**（硅基流动，直连 RTT 几十 ms）；
    # 亦可仅改 base_url/model 切回 OpenRouter 等境外服务（协议一致，代码无需改动）。
    # 密钥来自 .env（LINGXI_DENSE_EMBED_API_KEY）或系统环境变量
    dense_embed_api_key: str = ""
    dense_embed_base_url: str = "https://api.siliconflow.cn/v1"
    dense_embed_model: str = "BAAI/bge-m3"
    dense_embed_timeout: float = 30.0
    dense_embed_max_retries: int = 2

    # 检索参数（M3-3.6）：混合检索召回 Top10 → 精排 Top5
    # 候选数下调（20→10）用于降低精排耗时（首字延迟优化）：精排成本随候选数线性增长
    retrieve_candidate_k: int = 10
    retrieve_top_k: int = 5

    # Agent 运行时（M3-3.7）：单轮对话保留的近期消息数上限
    agent_history_max_messages: int = 20
    # 上下文窗口（M3-3.11）：保持近 N 轮原文，更早的旧消息压缩为摘要并回写 Java 会话
    agent_summary_enabled: bool = True
    agent_summary_max_chars: int = 600
    # 会话消息事件未携带租户/知识库时的兜底（Java 会话域补齐上下文的后续项）
    agent_default_tenant_id: str = "default"
    agent_default_kb_id: str = ""

    # Kafka 事件总线（M3-3.8 流式全链路）
    kafka_enabled: bool = False  # 关闭时不起消费协程，便于本地无 Kafka 场景启动服务
    kafka_bootstrap_servers: str = "127.0.0.1:9092"
    kafka_consumer_group: str = "lingxi-ai"
    kafka_topic_session_message: str = "lingxi.session.message"  # 消费：用户消息触发 AI
    kafka_topic_ai_chunk: str = "lingxi.ai.chunk"  # 生产：流式片段（Go 消费后推 WSS）
    kafka_topic_ai_reply: str = "lingxi.ai.reply"  # 生产：最终回答（Java 持久化）
    # 上下文窗口（M3-3.11）：生产：会话摘要（Java 回写会话，随下一轮事件带回）
    kafka_topic_session_summary: str = "lingxi.session.summary"

    # 知识库入库链路（M3-3.2：Java 上传 → 事件 → 本侧入库 → 状态回写）
    kafka_topic_kb_ingest: str = "kb.doc.ingest"  # 消费：文档入库触发（Java 生产）
    kafka_topic_kb_indexed: str = "kb.doc.indexed"  # 生产：入库结果（Java 消费后回写状态）
    kafka_topic_kb_delete: str = "kb.doc.delete"  # 消费：文档下线（清理向量库切片）
    kafka_kb_consumer_group: str = "lingxi-ai-kb"  # 与对话消费组隔离，独立位点
    kafka_kb_delete_consumer_group: str = "lingxi-ai-kb-delete"  # 下线清理独立消费组
    kb_download_timeout: float = 60.0  # 从预签名 URL 下载原始文档的超时（秒）

    # Rerank 精排（M3-3.6，云端 bge-reranker-v2-m3）
    # provider 取值：cloud（云端 REST，默认）| none（降级为 RRF 顺序）
    # 密钥来自 .env（LINGXI_RERANK_API_KEY）或系统环境变量；缺失时自动降级不阻断检索
    rerank_provider: str = "cloud"
    rerank_api_key: str = ""
    rerank_base_url: str = "https://api.siliconflow.cn/v1"
    rerank_model: str = "BAAI/bge-reranker-v2-m3"
    rerank_timeout: float = 10.0
    rerank_max_retries: int = 1

    # PII 脱敏（M3-3.9）：手机/身份证/银行卡/邮箱/地址在进入云端模型（LLM 与
    # embedding）前替换为占位符，输出侧再还原；地址为启发式识别，误伤业务文本时可关闭
    pii_enabled: bool = True
    pii_mask_address: bool = True

    # 评测中心（M3-3.10）：Golden Set 回归使用的独立租户/知识库（与业务数据隔离）
    eval_seed_tenant_id: str = "eval-tenant"
    eval_seed_kb_id: str = "eval-kb"
    eval_dataset_path: str = ""  # 空则用内置 Golden Set（app/evaluator/golden/cases.json）
    eval_report_dir: str = ""  # 空则用系统临时目录下的 lingxi-eval（报告与 Badcase 落盘）


settings = Settings()
