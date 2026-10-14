package com.lingxi.knowledge.domain;

import java.util.UUID;

/**
 * 知识分类：知识库内的文档归类（一级分类，命名在库内唯一）。
 *
 * <p>用于运营侧按分类组织文档与后续按分类过滤检索。</p>
 */
public class KbCategory {

    private static final int MAX_NAME_LEN = 128;

    private final String categoryId;
    private final String kbId;
    private final String tenantId;
    private String name;
    private final long createdAtMs;
    private long updatedAtMs;

    /** 新建分类。 */
    public KbCategory(String kbId, String tenantId, String name) {
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("知识库 ID 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        this.categoryId = UUID.randomUUID().toString();
        this.kbId = kbId;
        this.tenantId = tenantId;
        this.name = validateName(name);
        this.createdAtMs = System.currentTimeMillis();
        this.updatedAtMs = this.createdAtMs;
    }

    /** 仅供仓储从持久化数据恢复。 */
    public KbCategory(String categoryId, String kbId, String tenantId, String name,
                      long createdAtMs, long updatedAtMs) {
        this.categoryId = categoryId;
        this.kbId = kbId;
        this.tenantId = tenantId;
        this.name = name;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    /** 重命名分类。 */
    public void rename(String name) {
        this.name = validateName(name);
        this.updatedAtMs = System.currentTimeMillis();
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        if (name.length() > MAX_NAME_LEN) {
            throw new IllegalArgumentException("分类名称超过上限 " + MAX_NAME_LEN);
        }
        return name;
    }

    public String getCategoryId() {
        return categoryId;
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

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }
}
