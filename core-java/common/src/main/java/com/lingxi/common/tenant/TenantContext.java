package com.lingxi.common.tenant;

/**
 * 租户上下文持有者：按线程绑定当前请求的租户身份。
 *
 * <p>写入方只有两处：①gRPC 服务端拦截器 {@link TenantGuardInterceptor}（生产路径）；
 * ②单测/内部任务（显式设置）。业务代码只读，不自行推导租户，避免伪造租户的旁路。</p>
 *
 * <p>为什么用 ThreadLocal 而非 gRPC {@code Context}：gRPC 会在线程池中触发
 * {@code onHalfClose} 等回调，拦截器在 {@code interceptCall} 线程设置的值可能丢失；
 * 本实现改为在**每个回调执行线程**上设置/清理，保证业务方法执行期间上下文有效。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<TenantIdentity> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 绑定当前线程的租户身份（调用方负责在用完后 {@link #clear()}）。 */
    public static void set(TenantIdentity identity) {
        HOLDER.set(identity);
    }

    /** 清理当前线程绑定，避免线程复用时租户串号。 */
    public static void clear() {
        HOLDER.remove();
    }

    /** 当前租户身份；未绑定时返回 {@link TenantIdentity#NONE}。 */
    public static TenantIdentity current() {
        TenantIdentity identity = HOLDER.get();
        return identity == null ? TenantIdentity.NONE : identity;
    }

    /** 当前租户 ID；未绑定时为空串。 */
    public static String currentTenantId() {
        return current().tenantId();
    }

    /**
     * 强制要求租户上下文存在。
     *
     * @return 当前租户 ID（非空）
     * @throws MissingTenantContextException 上下文缺失时抛出（gRPC 层映射 {@code UNAUTHENTICATED}）
     */
    public static String requireTenantId() {
        TenantIdentity identity = current();
        if (!identity.hasTenant()) {
            throw new MissingTenantContextException("缺少租户上下文：请求未携带可信租户身份");
        }
        return identity.tenantId();
    }

    /** 当前租户身份是否归属于指定租户。 */
    public static boolean belongsTo(String tenantId) {
        return current().belongsTo(tenantId);
    }
}
