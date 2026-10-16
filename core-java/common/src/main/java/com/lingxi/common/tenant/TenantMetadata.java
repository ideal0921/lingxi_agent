package com.lingxi.common.tenant;

/**
 * 租户上下文的 gRPC metadata 键约定（M5-5.5）。
 *
 * <p>由 Go 网关在出站调用时写入；Java 侧拦截器读取后注入 {@link TenantContext}。
 * 键名统一带 {@code x-} 前缀，避免与业务自定义 metadata 冲突。</p>
 */
public final class TenantMetadata {

    /** 租户标识（必需，缺失即视为未认证）。 */
    public static final String KEY_TENANT_ID = "x-tenant-id";
    /** 用户标识（可选）。 */
    public static final String KEY_USER_ID = "x-user-id";
    /** 主体角色（可选）：user / agent / admin。 */
    public static final String KEY_ROLE = "x-role";

    private TenantMetadata() {
    }
}
