package com.lingxi.workflow.domain;

import java.util.UUID;

/**
 * 工作流聚合：元数据 + 当前草稿 + 版本计数，是发布与回滚的一致边界。
 *
 * <p>草稿以空串表示「无草稿」（数据库 MEDIUMTEXT 列不支持默认值，空串可避免空值类型歧义）。
 * 校验规则集中在构造器与行为方法内，非法入参直接拒绝。</p>
 */
public class Workflow {

    private static final int MAX_NAME_LEN = 128;
    private static final int MAX_DESC_LEN = 1000;
    private static final int MAX_PERCENT = 100;

    private final String workflowId;
    private final String tenantId;
    private String name;
    private String description;
    private WorkflowStatus status;
    private String draftDefinition;
    private int latestVersion;
    private int publishedVersion;
    /** 灰度版本号（M4-4.8；0 表示未启用灰度）。 */
    private int canaryVersion;
    /** 灰度分流比例 0~100（M4-4.8；0 表示关闭灰度）。 */
    private int canaryPercent;
    private final long createdAtMs;
    private long updatedAtMs;

    /** 新建工作流（启用状态、空草稿、未发布、未启用灰度）。 */
    public Workflow(String tenantId, String name, String description) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        this.workflowId = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.name = validateName(name);
        this.description = validateDescription(description);
        this.status = WorkflowStatus.ENABLED;
        this.draftDefinition = "";
        this.latestVersion = 0;
        this.publishedVersion = 0;
        this.canaryVersion = 0;
        this.canaryPercent = 0;
        this.createdAtMs = System.currentTimeMillis();
        this.updatedAtMs = this.createdAtMs;
    }

    /** 仅供仓储从持久化数据恢复（跳过默认初始化，字段由存储回填）。 */
    public Workflow(String workflowId, String tenantId, String name, String description,
                    WorkflowStatus status, String draftDefinition, int latestVersion,
                    int publishedVersion, int canaryVersion, int canaryPercent,
                    long createdAtMs, long updatedAtMs) {
        this.workflowId = workflowId;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.draftDefinition = draftDefinition == null ? "" : draftDefinition;
        this.latestVersion = latestVersion;
        this.publishedVersion = publishedVersion;
        this.canaryVersion = canaryVersion;
        this.canaryPercent = canaryPercent;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    /** 更新名称与描述（运营维护）。 */
    public void rename(String name, String description) {
        this.name = validateName(name);
        this.description = validateDescription(description);
        touch();
    }

    /** 启用/停用。 */
    public void changeStatus(WorkflowStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("状态不能为空");
        }
        this.status = status;
        touch();
    }

    /**
     * 写入草稿：保存草稿与「回滚到历史版本」共用此方法（二者语义相同，均为覆盖当前草稿）。
     * 回滚不新增版本、不改生效版本，运营复核后再发布即生成新版本号。
     */
    public void saveDraft(String definitionJson) {
        this.draftDefinition = definitionJson == null ? "" : definitionJson;
        touch();
    }

    /** 是否有草稿内容。 */
    public boolean hasDraft() {
        return draftDefinition != null && !draftDefinition.isBlank();
    }

    /**
     * 记录发布结果。
     *
     * @param version 新版本号
     * @param canary  是否灰度发布（M4-4.8）：灰度发布只推进 {@code latestVersion}，
     *                {@code publishedVersion} 保持不变——新版本先由 {@link #setRollout} 按比例生效
     */
    public void markPublished(int version, boolean canary) {
        if (version <= latestVersion) {
            throw new IllegalArgumentException("发布版本号必须大于当前最新版本号");
        }
        this.latestVersion = version;
        if (!canary) {
            this.publishedVersion = version;
        }
        touch();
    }

    /**
     * 设置灰度分流（M4-4.8）。
     *
     * @param canaryVersion 灰度版本号；{@code percent<=0} 时强制归 0（关闭灰度）
     * @param canaryPercent 分流比例 0~100
     */
    public void setRollout(int canaryVersion, int canaryPercent) {
        if (canaryPercent < 0 || canaryPercent > MAX_PERCENT) {
            throw new IllegalArgumentException("灰度比例必须在 0~" + MAX_PERCENT + " 之间");
        }
        if (canaryPercent > 0 && canaryVersion <= 0) {
            throw new IllegalArgumentException("开启灰度时必须指定灰度版本号");
        }
        this.canaryPercent = canaryPercent;
        this.canaryVersion = canaryPercent > 0 ? canaryVersion : 0;
        touch();
    }

    /** 是否启用灰度分流。 */
    public boolean hasRollout() {
        return canaryPercent > 0 && canaryVersion > 0;
    }

    private void touch() {
        this.updatedAtMs = System.currentTimeMillis();
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工作流名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LEN) {
            throw new IllegalArgumentException("工作流名称长度不能超过 " + MAX_NAME_LEN);
        }
        return trimmed;
    }

    private static String validateDescription(String description) {
        String trimmed = description == null ? "" : description.trim();
        if (trimmed.length() > MAX_DESC_LEN) {
            throw new IllegalArgumentException("工作流描述长度不能超过 " + MAX_DESC_LEN);
        }
        return trimmed;
    }

    public String getWorkflowId() {
        return workflowId;
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

    public WorkflowStatus getStatus() {
        return status;
    }

    public String getDraftDefinition() {
        return draftDefinition;
    }

    public int getLatestVersion() {
        return latestVersion;
    }

    public int getPublishedVersion() {
        return publishedVersion;
    }

    public int getCanaryVersion() {
        return canaryVersion;
    }

    public int getCanaryPercent() {
        return canaryPercent;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }
}
