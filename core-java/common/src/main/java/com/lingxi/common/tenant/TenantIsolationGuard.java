package com.lingxi.common.tenant;

/**
 * 越权拒绝的统一上报助手（M5-5.5）：把 {@link TenantAccessDeniedException} 中的事实
 * 落到审计端口，并保证**审计失败不影响主链路**（审计是旁路，不应放大故障）。
 */
public final class TenantIsolationGuard {

    private TenantIsolationGuard() {
    }

    /**
     * 上报越权事实。
     *
     * @param auditor  审计端口（允许为 null，表示未装配审计）
     * @param violation 越权事实
     */
    public static void report(TenantIsolationAuditor auditor, IsolationViolation violation) {
        if (auditor == null || violation == null) {
            return;
        }
        try {
            auditor.record(violation);
        } catch (RuntimeException ignored) {
            // 审计旁路异常不得中断业务拒绝流程
        }
    }

    /** 构造「当前请求主体访问其他租户资源」的越权事实。 */
    public static IsolationViolation violationOf(TenantIdentity identity, String resourceType,
                                                 String resourceId, String resourceTenantId,
                                                 String action, String detail) {
        return new IsolationViolation(identity.tenantId(), identity.userId(), identity.role(),
                resourceType, resourceId, resourceTenantId, action, detail);
    }
}
