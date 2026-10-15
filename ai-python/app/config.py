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
    # 记忆维护（摘要 / 画像合并）属**非关键路径**：硬性超时上限，避免单次抖动长时间占用
    # 资源（该路径已改为后台任务，但超时上限仍是必要的兜底——LLM 最坏耗时=providers×retries×timeout）
    agent_summary_timeout_seconds: float = 30.0

    # 跨会话长期记忆（M4-4.4）：用户级「画像摘要」存 Redis（KV，按 tenant+user 隔离）；
    # 仅在超窗压缩时顺带合并更新（普通短会话零额外模型调用），每轮直接注入提示词
    agent_memory_enabled: bool = True
    agent_memory_merge_enabled: bool = True
    agent_memory_max_chars: int = 400
    agent_memory_ttl_seconds: int = 2_592_000  # 30 天未活跃即过期，避免僵尸画像长期占用
    agent_memory_timeout_seconds: float = 1.0  # 单次 Redis 读写超时；超时即降级，不阻断问答
    # Redis（与 core-java application.yml 同源）；画像读写失败不影响问答主链路
    redis_host: str = "127.0.0.1"
    redis_port: int = 6379
    redis_db: int = 0
    redis_password: str = ""
    # RESP 协议版本：默认 2。redis-py 8.x 默认走 RESP3，连接时会先发 HELLO，
    # 而 HELLO 是 Redis 6.0 才引入的命令——本地 Redis 3.2.8 会报 unknown command。
    redis_protocol: int = 2

    # 人工接管联动（M4-4.5）：三类规则触发转人工 + 接管瞬间终止生成
    # 命中规则即跳过 AI 生成、回固定话术并通知 Java 转人工（WAITING_HUMAN）
    agent_handoff_enabled: bool = True
    agent_handoff_user_keywords: str = "转人工,人工客服,人工服务,找人工,转接人工"
    agent_handoff_emotion_keywords: str = (
        "投诉,举报,骗子,诈骗,垃圾,退钱,赔偿,维权,差评,监管,报警,告你们"
    )
    agent_handoff_emotion_min_hits: int = 2  # 情绪关键词命中数阈值（防单词误伤）
    # 低置信度：①检索命中为空（与分数量纲无关，默认启用）②精排分低于阈值（默认关闭）
    agent_handoff_empty_hits_enabled: bool = True
    agent_handoff_low_score_enabled: bool = False
    agent_handoff_low_score_threshold: float = 0.3
    agent_handoff_reply_text: str = "已为您转接人工客服，请稍候，正在为您安排坐席。"
    agent_handoff_interrupt_ttl_seconds: float = 300.0  # 中断标记 TTL（防堆积）
    kafka_topic_ai_handoff: str = "lingxi.ai.handoff"  # 生产：转人工请求（Java 消费）
    kafka_topic_session_takeover: str = "lingxi.session.takeover"  # 消费：接管通知（终止生成）
    kafka_takeover_consumer_group: str = "lingxi-ai-takeover"  # 接管通知独立消费组

    # 工具链（M4-4.1 / 4.2 / 4.3）：Function Calling + JSON Schema 强校验 + 白名单 + 全量审计
    agent_tools_enabled: bool = True
    tool_timeout_seconds: float = 5.0  # 单次工具调用超时（架构约束：< 5s）
    tool_max_retries: int = 1  # 失败重试次数（不含首次）
    tool_max_rounds: int = 3  # 工具调用轮次上限（防死循环）
    tool_audit_enabled: bool = True
    kafka_topic_tool_audit: str = "lingxi.tool.audit"  # 工具调用审计主题
    business_grpc_target: str = "127.0.0.1:9090"  # Java 业务域 gRPC 地址
    business_grpc_timeout_seconds: float = 5.0
    business_degrade_message: str = "抱歉，业务系统暂时无法查询，请稍后再试或转人工核实。"
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
    # 坐席快捷回复同步通道（M4-4.6）：gRPC 同步 + 独立并发闸门 + 超载回落异步
    suggest_grpc_enabled: bool = True
    suggest_grpc_port: int = 9095  # 避开 Java 9090 / Milvus 9091
    suggest_gate: int = 32  # 独立并发闸门（超出立即降级为异步）
    suggest_queue_wait_seconds: float = 0.0  # >0 才排队；默认不排队，避免坐席等待被放大
    suggest_timeout_seconds: float = 3.5  # 单次生成本身的上限（< Java 出站 4s < Go 5s）
    suggest_max_concurrent_rpcs: int = 64  # gRPC 硬顶（第二道保险）
    suggest_max_candidates: int = 3  # 候选条数上限
    kafka_topic_ai_suggest_request: str = "lingxi.ai.suggest.request"  # 消费：同步过载后的降级请求
    kafka_topic_ai_suggest_result: str = "lingxi.ai.suggest.result"  # 生产：异步建议（Go 只推坐席端）
    kafka_suggest_consumer_group: str = "lingxi-ai-suggest"  # 建议异步链路独立消费组
    # 消费活性（M4-4.5 加固）：单条处理超过 max_poll_interval_ms 会被踢出消费组，
    # 且 aiokafka 不会自动重新入组（表现为「AI 不再应答」且无异常日志）——
    # 故 handler 内不得有长任务（记忆维护已改后台任务），并对外提供重建退避配置。
    kafka_max_poll_interval_ms: int = 300_000
    kafka_consumer_restart_backoff_seconds: float = 5.0

    # 分析管道（M5-5.1）：LLM 用量埋点投递到该主题，由 Java 报表域消费落库。
    # 关闭后 AI 侧不再产出用量埋点（会话生命周期与满意度埋点由 Java 侧产出，不受此开关影响）。
    analytics_enabled: bool = True
    kafka_topic_analytics_event: str = "lingxi.analytics.event"

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

    # 多工作流运行时（M4-4.8）：按版本加载编排图 + 版本灰度路由
    # 总开关；关闭或未配置默认工作流时，行为与 M4-4.7 完全一致（走内置三节点图）
    agent_workflow_enabled: bool = True
    # 租户默认工作流 ID（空表示未接入编排；不同环境/租户可配不同工作流）
    agent_default_workflow_id: str = ""
    # Java 编排域 gRPC 地址（与业务工具域同一端口：Java 侧 gRPC 9090）
    agent_workflow_grpc_target: str = "127.0.0.1:9090"
    agent_workflow_grpc_timeout_seconds: float = 3.0
    # 快照（版本与灰度规则）缓存 TTL：灰度/发布变更的最长生效延迟
    agent_workflow_snapshot_ttl_seconds: float = 30.0
    # 已编译图缓存：编译非平凡，按 (workflow_id, version) 复用
    agent_workflow_graph_cache_size: int = 32
    agent_workflow_graph_ttl_seconds: float = 300.0
    # 图定义大小上限（与 Java lingxi.workflow.definition-max-bytes 对齐）
    agent_workflow_max_definition_bytes: int = 262144
    # 分支表达式长度上限（白名单求值器拒绝超长表达式）
    agent_workflow_max_expression_length: int = 200


settings = Settings()
