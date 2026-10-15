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
