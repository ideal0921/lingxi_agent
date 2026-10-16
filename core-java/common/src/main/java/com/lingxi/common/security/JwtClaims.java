package com.lingxi.common.security;

/**
 * 令牌身份声明（M5-5.6）：与 Go 网关解析的 claims 约定一致
 * （`tid` 租户、`sub` 用户 ID、`username`、`role`、`exp` 过期时间）。
 *
 * @param tenantId      租户标识
 * @param userId        用户标识
 * @param username      登录名（审计与展示用）
 * @param role          角色：admin / agent / user
 * @param expiresAtMillis 过期时间（epoch ms）
 */
public record JwtClaims(String tenantId, String userId, String username, String role, long expiresAtMillis) {

    /** 角色常量与 Go 网关、前端菜单保持一致。 */
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_AGENT = "agent";
    public static final String ROLE_USER = "user";

    /** 是否具备租户与用户身份（用于判定令牌有效性）。 */
    public boolean hasIdentity() {
        return tenantId != null && !tenantId.isBlank() && userId != null && !userId.isBlank();
    }

    /** 是否超级角色（admin）。 */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
