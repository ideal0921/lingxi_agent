-- M2-B 工单域表结构（启动时幂等建表）
-- 说明：会话域自 M5-5.7 起亦落 MySQL（见文末 lingxi_session / lingxi_session_message），工单与会话均为持久化存储。

CREATE TABLE IF NOT EXISTS lingxi_ticket (
    ticket_id   VARCHAR(64)  NOT NULL COMMENT '工单 ID（UUID）',
    tenant_id   VARCHAR(64)  NOT NULL COMMENT '租户 ID',
    session_id  VARCHAR(64)  NOT NULL COMMENT '来源会话 ID',
    user_id     VARCHAR(64)  NOT NULL COMMENT '用户 ID',
    agent_id    VARCHAR(64)  NOT NULL COMMENT '处理坐席 ID',
    title       VARCHAR(255) NOT NULL COMMENT '工单标题',
    description VARCHAR(4000) NOT NULL DEFAULT '' COMMENT '工单描述',
    status      VARCHAR(16)  NOT NULL COMMENT '状态：CREATED/PROCESSING/CLOSED',
    created_at  BIGINT       NOT NULL COMMENT '创建时间（epoch ms）',
    updated_at  BIGINT       NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (ticket_id),
    KEY idx_agent_status (agent_id, status),
    KEY idx_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单表';

-- M3-3.2 知识库域表结构（知识库 / 分类 / 文档元数据 / 分片上传会话）

CREATE TABLE IF NOT EXISTS lingxi_kb (
    kb_id       VARCHAR(64)   NOT NULL COMMENT '知识库 ID（UUID）',
    tenant_id   VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    name        VARCHAR(128)  NOT NULL COMMENT '知识库名称',
    description VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '描述',
    status      VARCHAR(16)   NOT NULL COMMENT '状态：ENABLED/DISABLED',
    created_at  BIGINT        NOT NULL COMMENT '创建时间（epoch ms）',
    updated_at  BIGINT        NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (kb_id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识库表';

CREATE TABLE IF NOT EXISTS lingxi_kb_category (
    category_id VARCHAR(64)  NOT NULL COMMENT '分类 ID（UUID）',
    kb_id       VARCHAR(64)  NOT NULL COMMENT '所属知识库',
    tenant_id   VARCHAR(64)  NOT NULL COMMENT '租户 ID',
    name        VARCHAR(128) NOT NULL COMMENT '分类名称（库内唯一）',
    created_at  BIGINT       NOT NULL COMMENT '创建时间（epoch ms）',
    updated_at  BIGINT       NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (category_id),
    UNIQUE KEY uk_kb_name (kb_id, name),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识分类表';

CREATE TABLE IF NOT EXISTS lingxi_kb_document (
    doc_id         VARCHAR(64)   NOT NULL COMMENT '文档 ID（UUID，版本间稳定）',
    kb_id          VARCHAR(64)   NOT NULL COMMENT '所属知识库',
    tenant_id      VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    category_id    VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '分类 ID（可空）',
    title          VARCHAR(255)  NOT NULL COMMENT '文档标题',
    file_name      VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    doc_type       VARCHAR(16)   NOT NULL COMMENT '文档类型：pdf/docx/pptx/sheet/html/markdown/text',
    version        INT           NOT NULL COMMENT '当前版本号（从 1 递增）',
    content_hash   CHAR(64)      NOT NULL COMMENT '内容 SHA-256（库内唯一，用于重复上传去重）',
    size_bytes     BIGINT        NOT NULL COMMENT '文件大小（字节）',
    object_key     VARCHAR(512)  NOT NULL COMMENT '对象存储路径 tenant/kb/doc/version/file',
    tags           VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '标签（逗号分隔）',
    status         VARCHAR(16)   NOT NULL COMMENT '切片状态：PENDING/INDEXED/FAILED/DELETED',
    status_message VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '状态说明（失败原因等）',
    child_count    INT           NOT NULL DEFAULT 0 COMMENT '子块数量（入库结果）',
    parent_count   INT           NOT NULL DEFAULT 0 COMMENT '父块分组数量（入库结果）',
    created_at     BIGINT        NOT NULL COMMENT '创建时间（epoch ms）',
    updated_at     BIGINT        NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (doc_id),
    UNIQUE KEY uk_kb_hash (kb_id, content_hash),
    KEY idx_kb (kb_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识文档元数据表';

CREATE TABLE IF NOT EXISTS lingxi_kb_upload (
    upload_id      VARCHAR(64)   NOT NULL COMMENT '上传会话 ID（UUID）',
    tenant_id      VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    kb_id          VARCHAR(64)   NOT NULL COMMENT '知识库 ID',
    doc_id         VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '目标文档 ID（空则新建文档）',
    title          VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '文档标题',
    file_name      VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    category_id    VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '分类 ID',
    tags           VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '标签（逗号分隔）',
    total_parts    INT           NOT NULL COMMENT '分片总数',
    part_size      INT           NOT NULL COMMENT '分片建议大小（字节）',
    received_parts VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '已收分片序号（逗号分隔）',
    status         VARCHAR(16)   NOT NULL COMMENT '会话状态：OPEN/COMPLETED/ABORTED',
    created_at     BIGINT        NOT NULL COMMENT '创建时间（epoch ms）',
    updated_at     BIGINT        NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (upload_id),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分片上传会话表';

-- M4-4.2 业务工具域表结构（订单 / 物流 / 退换货受理单，供 AI 域 Function Calling 查询与提交）

CREATE TABLE IF NOT EXISTS biz_order (
    order_no      VARCHAR(64)   NOT NULL COMMENT '订单号',
    tenant_id     VARCHAR(64)   NOT NULL COMMENT '租户 ID（强制隔离的检索条件）',
    user_id       VARCHAR(64)   NOT NULL COMMENT '下单用户 ID',
    status        VARCHAR(16)   NOT NULL COMMENT '订单状态：PAID/SHIPPED/DELIVERED/REFUNDING/CLOSED',
    amount        DECIMAL(10,2) NOT NULL COMMENT '订单金额（元，两位小数）',
    items_summary VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '商品摘要（名称×数量，多件以「、」分隔）',
    created_at    BIGINT        NOT NULL COMMENT '创建时间（epoch ms）',
    PRIMARY KEY (order_no),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务订单表';

CREATE TABLE IF NOT EXISTS biz_logistics (
    order_no     VARCHAR(64)  NOT NULL COMMENT '订单号',
    tenant_id    VARCHAR(64)  NOT NULL COMMENT '租户 ID（强制隔离的检索条件）',
    carrier      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '承运商',
    tracking_no  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '运单号',
    status       VARCHAR(24)  NOT NULL COMMENT '物流状态：PENDING/PICKED/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION',
    latest_event VARCHAR(500) NOT NULL DEFAULT '' COMMENT '最新物流动态（自然语言）',
    updated_at   BIGINT       NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (order_no),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '物流轨迹表';

CREATE TABLE IF NOT EXISTS biz_return_request (
    return_id   VARCHAR(64)   NOT NULL COMMENT '受理单号（UUID）',
    tenant_id   VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    order_no    VARCHAR(64)   NOT NULL COMMENT '订单号',
    return_type VARCHAR(16)   NOT NULL COMMENT '退换货类型：REFUND/EXCHANGE',
    reason      VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '用户申请原因',
    status      VARCHAR(16)   NOT NULL COMMENT '申请状态：ACCEPTED/REJECTED',
    created_at  BIGINT        NOT NULL COMMENT '创建时间（epoch ms）',
    PRIMARY KEY (return_id),
    KEY idx_order_tenant_status (order_no, tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '退换货受理单表';

-- 样本数据（幂等预置，供 M4-4.2 端到端联调；重复启动不产生脏数据）
INSERT INTO biz_order (order_no, tenant_id, user_id, status, amount, items_summary, created_at)
VALUES ('LN20240815001', 't-ctx', 'u-1001', 'DELIVERED', 199.00, '保温杯×1', 1723680000000),
       ('LN20240815002', 't-ctx', 'u-1002', 'SHIPPED', 88.50, '数据线×2、充电头×1', 1723680000000),
       ('LN20240815003', 'tenant-1', 'u-1003', 'PAID', 59.90, '鼠标垫×1', 1723680000000)
ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id), user_id = VALUES(user_id),
                        status = VALUES(status), amount = VALUES(amount),
                        items_summary = VALUES(items_summary);

INSERT INTO biz_logistics (order_no, tenant_id, carrier, tracking_no, status, latest_event, updated_at)
VALUES ('LN20240815002', 't-ctx', '顺丰速运', 'SF1234567890', 'IN_TRANSIT', '快件已到达杭州转运中心', 1723690800000)
ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id), carrier = VALUES(carrier),
                        tracking_no = VALUES(tracking_no), status = VALUES(status),
                        latest_event = VALUES(latest_event), updated_at = VALUES(updated_at);

-- M4-4.7 编排域表结构（工作流元数据 + 不可变版本快照）
-- 说明：draft_definition / definition 用 MEDIUMTEXT（MySQL 的 TEXT 系不支持默认值），
-- 草稿为空时写入空串而非 NULL，避免 JdbcTemplate 传空值的类型歧义。

CREATE TABLE IF NOT EXISTS lingxi_workflow (
    workflow_id       VARCHAR(64)  NOT NULL COMMENT '工作流 ID（UUID）',
    tenant_id         VARCHAR(64)  NOT NULL COMMENT '租户 ID',
    name              VARCHAR(128) NOT NULL COMMENT '工作流名称',
    description       VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '描述',
    status            VARCHAR(16)  NOT NULL COMMENT '状态：ENABLED/DISABLED',
    draft_definition  MEDIUMTEXT   NOT NULL COMMENT '当前草稿图定义（JSON；空串表示无草稿）',
    latest_version    INT          NOT NULL DEFAULT 0 COMMENT '最新版本号（0 表示从未发布）',
    published_version INT          NOT NULL DEFAULT 0 COMMENT '生效版本号（0 表示未发布）',
    canary_version    INT          NOT NULL DEFAULT 0 COMMENT '灰度版本号（M4-4.8；0 表示未启用灰度）',
    canary_percent    INT          NOT NULL DEFAULT 0 COMMENT '灰度分流比例（M4-4.8；0~100，0 表示关闭）',
    created_at        BIGINT       NOT NULL COMMENT '创建时间（epoch ms）',
    updated_at        BIGINT       NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (workflow_id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工作流表';

CREATE TABLE IF NOT EXISTS lingxi_workflow_version (
    version_id   VARCHAR(64)  NOT NULL COMMENT '版本 ID（UUID）',
    workflow_id  VARCHAR(64)  NOT NULL COMMENT '所属工作流',
    tenant_id    VARCHAR(64)  NOT NULL COMMENT '租户 ID',
    version      INT          NOT NULL COMMENT '版本号（自 1 递增）',
    definition   MEDIUMTEXT   NOT NULL COMMENT '图定义快照（JSON，发布即冻结）',
    note         VARCHAR(255) NOT NULL DEFAULT '' COMMENT '发布说明',
    operator     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '发布/回滚操作者',
    published_at BIGINT       NOT NULL COMMENT '发布时间（epoch ms）',
    created_at   BIGINT       NOT NULL COMMENT '创建时间（epoch ms）',
    PRIMARY KEY (version_id),
    UNIQUE KEY uk_workflow_version (workflow_id, version),
    KEY idx_workflow (workflow_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工作流版本快照表';

-- M4-4.8 灰度升级：既有库的 lingxi_workflow 由 IF NOT EXISTS 建表语句无法追加新列
-- （MySQL 不支持 ADD COLUMN IF NOT EXISTS），故用 information_schema 判断后动态 DDL，
-- 保证脚本可重复执行（列已存在时执行 SELECT 1 占位）。

SET @add_canary_version := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE lingxi_workflow ADD COLUMN canary_version INT NOT NULL DEFAULT 0 COMMENT ''灰度版本号（M4-4.8；0 表示未启用灰度）'' AFTER published_version',
              'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lingxi_workflow' AND COLUMN_NAME = 'canary_version');
PREPARE stmt_canary_version FROM @add_canary_version;
EXECUTE stmt_canary_version;
DEALLOCATE PREPARE stmt_canary_version;

SET @add_canary_percent := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE lingxi_workflow ADD COLUMN canary_percent INT NOT NULL DEFAULT 0 COMMENT ''灰度分流比例（M4-4.8；0~100，0 表示关闭）'' AFTER canary_version',
              'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lingxi_workflow' AND COLUMN_NAME = 'canary_percent');
PREPARE stmt_canary_percent FROM @add_canary_percent;
EXECUTE stmt_canary_percent;
DEALLOCATE PREPARE stmt_canary_percent;

-- M5-5.1 数据分析管道表结构（分析明细 + 会话漏斗 + Token 日聚合 + 满意度）
-- 说明：会话域为内存实现，故分析明细是漏斗的**唯一事实源**；明细主键 event_id 保证 Kafka 重投幂等。
-- 本期落 MySQL（仓储经依赖倒置端口，将来可平滑替换为 ClickHouse）。

CREATE TABLE IF NOT EXISTS lingxi_analytics_event (
    event_id    VARCHAR(64)  NOT NULL COMMENT '事件 ID（生产端生成一次，重投同 ID）',
    event_type  VARCHAR(32)  NOT NULL COMMENT 'session_lifecycle / llm_usage / session_feedback',
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 ID',
    session_id  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '会话 ID',
    occurred_at BIGINT       NOT NULL COMMENT '事件发生时间（epoch ms）',
    payload     MEDIUMTEXT   NOT NULL COMMENT '事件体原文（JSON）',
    PRIMARY KEY (event_id),
    KEY idx_type_time (event_type, occurred_at),
    KEY idx_tenant_time (tenant_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分析明细事件表';

CREATE TABLE IF NOT EXISTS lingxi_report_session_funnel (
    session_id        VARCHAR(64) NOT NULL COMMENT '会话 ID',
    tenant_id         VARCHAR(64) NOT NULL DEFAULT '' COMMENT '租户 ID',
    channel           VARCHAR(32) NOT NULL DEFAULT '' COMMENT '接入渠道（建会话时写入，首次写入为准）',
    created_ms        BIGINT      NULL COMMENT '建会话时间（最早）',
    first_user_msg_ms BIGINT      NULL COMMENT '首条用户消息时间（最早）',
    first_ai_reply_ms BIGINT      NULL COMMENT '首次 AI 应答时间（最早）',
    handoff_ms        BIGINT      NULL COMMENT '首次转人工/接管时间（最早）',
    closed_ms         BIGINT      NULL COMMENT '关闭时间（最早）',
    updated_at        BIGINT      NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (session_id),
    KEY idx_tenant_created (tenant_id, created_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话漏斗表（一会话一行，各阶段存最早时间戳）';

CREATE TABLE IF NOT EXISTS lingxi_report_token_daily (
    stat_date          DATE        NOT NULL COMMENT '统计日（Asia/Shanghai）',
    tenant_id          VARCHAR(64) NOT NULL DEFAULT '' COMMENT '租户 ID',
    provider           VARCHAR(32) NOT NULL DEFAULT '' COMMENT '模型提供方',
    model              VARCHAR(64) NOT NULL DEFAULT '' COMMENT '模型名',
    requests           INT         NOT NULL DEFAULT 0 COMMENT 'LLM 调用次数（工具多轮各计一次）',
    prompt_tokens      BIGINT      NOT NULL DEFAULT 0 COMMENT '输入 token 合计',
    completion_tokens  BIGINT      NOT NULL DEFAULT 0 COMMENT '输出 token 合计',
    estimated_requests INT         NOT NULL DEFAULT 0 COMMENT '其中按字符数估算的条数',
    first_token_samples INT        NOT NULL DEFAULT 0 COMMENT '有首字延迟样本的条数（平均值分母）',
    first_token_ms_sum BIGINT       NOT NULL DEFAULT 0 COMMENT '首字延迟合计（ms）',
    updated_at         BIGINT      NOT NULL COMMENT '更新时间（epoch ms）',
    PRIMARY KEY (stat_date, tenant_id, provider, model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Token 消耗日聚合表';

CREATE TABLE IF NOT EXISTS lingxi_report_feedback (
    feedback_id VARCHAR(64)  NOT NULL COMMENT '反馈 ID（UUID）',
    session_id  VARCHAR(64)  NOT NULL COMMENT '会话 ID',
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 ID（由会话域解析，前端不传）',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户 ID',
    rating      TINYINT      NOT NULL COMMENT '评分 1~5',
    comment     VARCHAR(500) NOT NULL DEFAULT '' COMMENT '评价内容',
    tags        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '标签（逗号分隔）',
    created_at  BIGINT       NOT NULL COMMENT '评价时间（epoch ms）',
    PRIMARY KEY (feedback_id),
    UNIQUE KEY uk_session (session_id),
    KEY idx_tenant_time (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话满意度表（一会话一条，后评覆盖）';

-- M5-5.6 用户/权限域表结构（账号表：密码登录与 RBAC）
-- 说明：口令以 BCrypt 哈希存储，明文永不落库；用户名租户内唯一。

CREATE TABLE IF NOT EXISTS lingxi_user (
    user_id       VARCHAR(64)  NOT NULL COMMENT '用户 ID（UUID）',
    tenant_id     VARCHAR(64)  NOT NULL COMMENT '租户 ID',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名（租户内唯一）',
    password_hash VARCHAR(100) NOT NULL COMMENT '口令哈希（BCrypt）',
    display_name  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '展示名',
    role          VARCHAR(16)  NOT NULL COMMENT '角色：admin/agent/user',
    status        VARCHAR(16)  NOT NULL COMMENT '状态：ENABLED/DISABLED',
    created_at    BIGINT       NOT NULL COMMENT '创建时间（epoch ms）',
    updated_at    BIGINT       NOT NULL COMMENT '更新时间（epoch ms）',
    last_login_at BIGINT       NOT NULL DEFAULT 0 COMMENT '最近登录时间（epoch ms，0 表示从未登录）',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_tenant_username (tenant_id, username),
    KEY idx_tenant_role (tenant_id, role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账号表（M5-5.6 密码登录与 RBAC）';

-- M5-5.6 操作日志表（只追加，不更新）：谁在何时对哪个租户做了什么
-- 说明：审计为旁路写入，失败不阻断主链路；detail 不得包含口令等敏感信息。

CREATE TABLE IF NOT EXISTS lingxi_operation_log (
    log_id        VARCHAR(64)   NOT NULL COMMENT '日志 ID（UUID）',
    tenant_id     VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '租户 ID',
    user_id       VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '操作主体用户 ID（登录失败时为空）',
    username      VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '操作主体登录名',
    role          VARCHAR(16)   NOT NULL DEFAULT '' COMMENT '操作主体角色',
    module        VARCHAR(32)   NOT NULL COMMENT '模块：auth/user/security/knowledge/workflow/session/ticket/report',
    action        VARCHAR(32)   NOT NULL COMMENT '动作：login/logout/create/update/delete/isolation_denied…',
    resource_type VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '资源类型（可空）',
    resource_id   VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '资源标识（可空）',
    result        VARCHAR(16)   NOT NULL COMMENT '结果：SUCCESS/FAILED/DENIED',
    detail        VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '结果说明（不含敏感信息）',
    client_ip     VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '来源 IP',
    user_agent    VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '来源 User-Agent',
    elapsed_ms    BIGINT        NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    created_at    BIGINT        NOT NULL COMMENT '记录时间（epoch ms）',
    PRIMARY KEY (log_id),
    KEY idx_tenant_time (tenant_id, created_at),
    KEY idx_module_action_time (module, action, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志表（M5-5.6 审计）';

-- M5-5.7 会话域表结构（会话主表 + 消息表）：会话与消息由进程内存迁至 MySQL，服务重启不丢历史。
-- 说明：message_count / summary 冗余在会话主表，用户侧历史列表无需回表读消息即可展示；
-- 消息以 (session_id, seq) 为主键，保证同一会话内序号唯一且有序（断连续传游标从 1 递增）；
-- 重复写入由仓储的 ON DUPLICATE KEY UPDATE 兜底幂等（Kafka 至少一次语义下的重投）。

CREATE TABLE IF NOT EXISTS lingxi_session (
    session_id        VARCHAR(64)   NOT NULL COMMENT '会话 ID（UUID）',
    tenant_id         VARCHAR(64)   NOT NULL COMMENT '租户 ID（隔离条件）',
    user_id           VARCHAR(64)   NOT NULL COMMENT '会话归属用户 ID',
    channel           VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '接入渠道：web/h5/app/wechat/feishu',
    state             VARCHAR(16)   NOT NULL COMMENT '状态：INIT/AI_ACTIVE/WAITING_HUMAN/HUMAN_CHAT/CLOSED',
    agent_id          VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '接管坐席 ID（未接管为空）',
    summary           VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '长程记忆摘要（AI 域压缩旧消息后回写）',
    created_at_ms     BIGINT        NOT NULL COMMENT '建会话时间（epoch ms）',
    last_active_at_ms BIGINT        NOT NULL COMMENT '最近活跃时间（epoch ms，列表倒序排序键）',
    message_count     INT           NOT NULL DEFAULT 0 COMMENT '消息条数（冗余列，列表免回表）',
    PRIMARY KEY (session_id),
    KEY idx_tenant_user_active (tenant_id, user_id, last_active_at_ms DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话表（M5-5.7 持久化）';

CREATE TABLE IF NOT EXISTS lingxi_session_message (
    session_id    VARCHAR(64)   NOT NULL COMMENT '会话 ID',
    seq           BIGINT        NOT NULL COMMENT '会话内自增序号（断连续传游标，从 1 起）',
    message_id    VARCHAR(64)   NOT NULL COMMENT '全局消息 ID（UUID / ai-<uuid>，落库幂等去重键）',
    sender        VARCHAR(16)   NOT NULL COMMENT '发送者：user/agent/system',
    sender_id     VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '发送者身份（用户 ID / 坐席 ID / ai）',
    content       VARCHAR(4000) NOT NULL DEFAULT '' COMMENT '消息内容',
    created_at_ms BIGINT        NOT NULL COMMENT '消息时间（epoch ms）',
    PRIMARY KEY (session_id, seq),
    KEY idx_message_id (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话消息表（M5-5.7 持久化）';

-- M5-5.9 API 接口链路追踪表
-- 一行 = 一个 span（一次调用）。三端（gateway-go / core-java / ai-python）以同一 trace_id 贯穿，
-- 管理端按 trace_id 聚合即可还原完整链路时间线。**不落请求/响应体**（PII 合规红线）。
CREATE TABLE IF NOT EXISTS lingxi_api_trace (
    span_id        VARCHAR(64)  NOT NULL COMMENT 'Span ID（UUID）',
    trace_id       VARCHAR(64)  NOT NULL COMMENT '链路 ID（网关生成，三端贯穿）',
    parent_span_id VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '上游 Span（用于还原调用关系，根为空）',
    service        VARCHAR(32)  NOT NULL COMMENT '来源服务：gateway-go/core-java/ai-python',
    component      VARCHAR(16)  NOT NULL COMMENT '调用类型：HTTP/GRPC/KAFKA/WSS',
    entry          VARCHAR(255) NOT NULL COMMENT '入口标识：HTTP 路径 / gRPC 全限定方法 / Kafka topic',
    http_method    VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'HTTP 方法（非 HTTP 为空）',
    status         VARCHAR(16)  NOT NULL COMMENT '结果：OK/ERROR',
    status_code    INT          NOT NULL DEFAULT 0 COMMENT '状态码（HTTP 状态码 / gRPC 状态码，无则 0）',
    tenant_id      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 ID',
    user_id        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '主体 ID',
    role           VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '角色',
    client_ip      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '来源 IP',
    elapsed_ms     BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时（ms）',
    error_msg      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '错误摘要（截断，不含请求体）',
    started_at     BIGINT       NOT NULL COMMENT '开始时间（epoch ms）',
    PRIMARY KEY (span_id),
    KEY idx_trace (trace_id),
    KEY idx_tenant_time (tenant_id, started_at),
    KEY idx_time (started_at),
    KEY idx_entry (entry)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'API 接口链路追踪 Span 表';
