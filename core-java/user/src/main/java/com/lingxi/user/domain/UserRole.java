package com.lingxi.user.domain;

/**
 * 账号角色（M5-5.6）：与网关路由校验、Java {@code @RequireRole} 及前端菜单保持同一取值。
 */
public enum UserRole {

    /** 平台管理员：全部能力。 */
    ADMIN("admin"),
    /** 客服坐席：工作台与报表。 */
    AGENT("agent"),
    /** 终端用户：门户会话与评价。 */
    USER("user");

    private final String code;

    UserRole(String code) {
        this.code = code;
    }

    /** 令牌与接口中使用的角色码。 */
    public String code() {
        return code;
    }

    /** 解析角色码；非法值返回 null（调用方据此拒绝）。 */
    public static UserRole fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toLowerCase();
        for (UserRole role : values()) {
            if (role.code.equals(normalized)) {
                return role;
            }
        }
        return null;
    }
}
