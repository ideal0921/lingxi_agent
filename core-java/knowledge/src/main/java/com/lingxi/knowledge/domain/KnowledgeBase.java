package com.lingxi.knowledge.domain;

import java.util.UUID;

/**
 * 知识库聚合：租户下的一组知识文档，是检索隔离（tenant_id 过滤）与权限归属的边界。
 *
 * <p>校验规则集中在构造器与行为方法内，非法入参直接拒绝，保持领域层单一权威。</p>
 */
public class KnowledgeBase {

    private static final int MAX_NAME_LEN = 128;
    private static final int MAX_DESC_LEN = 1000;

    private final String kbId;
    private final String tenantId;
    private String name;
    private String description;
    private KbStatus status;
    private final long createdAtMs;
    private long updatedAtMs;

    /** 新建知识库（默认启用）。 */
    public KnowledgeBase(String tenantId, String name, String description) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        this.kbId = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.name = validateName(name);
        this.description = validateDescription(description);
        this.status = KbStatus.ENABLED;
        this.createdAtMs = System.currentTimeMillis();
        this.updatedAtMs = this.createdAtMs;
    }

    /** 仅供仓储从持久化数据恢复（跳过默认初始化，字段由存储回填）。 */
    public KnowledgeBase(String kbId, String tenantId, String name, String description,
                         KbStatus status, long createdAtMs, long updatedAtMs) {
        this.kbId = kbId;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    /** 更新名称与描述（用于重命名与运营维护）。 */
    public void updateInfo(String name, String description) {
        this.name = validateName(name);
        this.description = validateDescription(description);
        touch();
    }

    /** 切换启停状态。 */
    public void changeStatus(KbStatus target) {
        if (target == null) {
            throw new IllegalArgumentException("目标状态不能为空");
        }
        this.status = target;
        touch();
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        if (name.length() > MAX_NAME_LEN) {
            throw new IllegalArgumentException("知识库名称超过上限 " + MAX_NAME_LEN);
        }
        return name;
    }

    private static String validateDescription(String description) {
        String value = description == null ? "" : description;
        if (value.length() > MAX_DESC_LEN) {
            throw new IllegalArgumentException("知识库描述超过上限 " + MAX_DESC_LEN);
        }
        return value;
    }

    private void touch() {
        this.updatedAtMs = System.currentTimeMillis();
    }

    public String getKbId() {
        return kbId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public KbStatus getStatus() {
        return status;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }
}
