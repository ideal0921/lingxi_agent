package com.lingxi.workflow.domain;

import java.util.UUID;

/**
 * 工作流版本快照：发布即冻结、不可修改；回滚时仅读取其 definition 覆盖草稿。
 */
public class WorkflowVersion {

    private final String versionId;
    private final String workflowId;
    private final String tenantId;
    private final int version;
    private final String definition;
    private final String note;
    private final String operator;
    private final long publishedAtMs;
    private final long createdAtMs;

    /** 发布新版本（版本号由服务层按「最新版本号 + 1」计算）。 */
    public WorkflowVersion(String workflowId, String tenantId, int version,
                           String definition, String note, String operator) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("工作流 ID 不能为空");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("版本号必须为正整数");
        }
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("版本图定义不能为空");
        }
        this.versionId = UUID.randomUUID().toString();
        this.workflowId = workflowId;
        this.tenantId = tenantId;
        this.version = version;
        this.definition = definition;
        this.note = note == null ? "" : note.trim();
        this.operator = operator == null ? "" : operator.trim();
        this.publishedAtMs = System.currentTimeMillis();
        this.createdAtMs = this.publishedAtMs;
    }

    /** 仅供仓储从持久化数据恢复。 */
    public WorkflowVersion(String versionId, String workflowId, String tenantId, int version,
                           String definition, String note, String operator,
                           long publishedAtMs, long createdAtMs) {
        this.versionId = versionId;
        this.workflowId = workflowId;
        this.tenantId = tenantId;
        this.version = version;
        this.definition = definition;
        this.note = note;
        this.operator = operator;
        this.publishedAtMs = publishedAtMs;
        this.createdAtMs = createdAtMs;
    }

    public String getVersionId() {
        return versionId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getVersion() {
        return version;
    }

    public String getDefinition() {
        return definition;
    }

    public String getNote() {
        return note;
    }

    public String getOperator() {
        return operator;
    }

    public long getPublishedAtMs() {
        return publishedAtMs;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }
}
