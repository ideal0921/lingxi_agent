-- M2-B 工单域表结构（启动时幂等建表）
-- 说明：当前仅工单域落 MySQL；会话域仍为内存实现，后续迁移时在此追加建表脚本。

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
