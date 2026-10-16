package com.lingxi.user.domain;

import java.util.UUID;

/**
 * 账号聚合实体（M5-5.6）：租户内账号，持有 BCrypt 口令哈希、角色与状态。
 *
 * <p>约束：**明文口令永不进入实体**——实体只保存哈希；口令比对由应用层用
 * {@code PasswordEncoder} 完成，实体不依赖任何框架类型。</p>
 */
public class LingxiUser {

    /** 登录名长度与字符集约束（避免怪异登录名带来的展示与审计问题）。 */
    private static final int MIN_USERNAME_LEN = 3;
    private static final int MAX_USERNAME_LEN = 32;
    private static final int MAX_DISPLAY_NAME_LEN = 64;

    private final String userId;
    private final String tenantId;
    private final String username;
    private final long createdAtMs;

    private String passwordHash;
    private String displayName;
    private UserRole role;
    private UserStatus status;
    private long updatedAtMs;
    private long lastLoginAtMs;

    /** 新建账号（口令哈希由应用层用 BCrypt 生成后传入）。 */
    public LingxiUser(String tenantId, String username, String passwordHash,
                      String displayName, UserRole role) {
        validateUsername(username);
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户不能为空");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("口令哈希不能为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        this.userId = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.username = username.trim();
        this.passwordHash = passwordHash;
        this.displayName = normalizeDisplayName(displayName);
        this.role = role;
        this.status = UserStatus.ENABLED;
        this.createdAtMs = System.currentTimeMillis();
        this.updatedAtMs = this.createdAtMs;
        this.lastLoginAtMs = 0L;
    }

    /** 从持久化恢复（跳过新建校验，字段由存储回填）。 */
    public LingxiUser(String userId, String tenantId, String username, String passwordHash,
                      String displayName, UserRole role, UserStatus status,
                      long createdAtMs, long updatedAtMs, long lastLoginAtMs) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName == null ? "" : displayName;
        this.role = role;
        this.status = status;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
        this.lastLoginAtMs = lastLoginAtMs;
    }

    /** 是否允许登录（仅启用状态可登录）。 */
    public boolean canLogin() {
        return status == UserStatus.ENABLED;
    }

    /** 重置口令哈希并刷新更新时间。 */
    public void changePassword(String newPasswordHash, long atMs) {
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new IllegalArgumentException("口令哈希不能为空");
        }
        this.passwordHash = newPasswordHash;
        this.updatedAtMs = atMs;
    }

    /** 变更启用状态。 */
    public void changeStatus(UserStatus target, long atMs) {
        if (target == null) {
            throw new IllegalArgumentException("状态不能为空");
        }
        this.status = target;
        this.updatedAtMs = atMs;
    }

    /** 记录最近登录时间（登录成功后调用）。 */
    public void markLogin(long atMs) {
        this.lastLoginAtMs = atMs;
        this.updatedAtMs = atMs;
    }

    public String getUserId() {
        return userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }

    public long getLastLoginAtMs() {
        return lastLoginAtMs;
    }

    /** 登录名校验：长度 + 字符集（字母数字与 _ . -），拒绝空白与超长。 */
    public static void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String trimmed = username.trim();
        if (trimmed.length() < MIN_USERNAME_LEN || trimmed.length() > MAX_USERNAME_LEN) {
            throw new IllegalArgumentException("用户名长度必须在 " + MIN_USERNAME_LEN + "~" + MAX_USERNAME_LEN + " 之间");
        }
        if (!trimmed.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("用户名仅支持字母、数字、下划线、点与中划线");
        }
    }

    private static String normalizeDisplayName(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.length() > MAX_DISPLAY_NAME_LEN) {
            throw new IllegalArgumentException("展示名长度超过上限 " + MAX_DISPLAY_NAME_LEN);
        }
        return value;
    }
}
