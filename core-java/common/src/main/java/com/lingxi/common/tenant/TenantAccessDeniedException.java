package com.lingxi.common.tenant;

/**
 * 跨租户越权访问异常（M5-5.5）：目标资源存在但归属其他租户。
 *
 * <p>业务域在发现越权时抛出；gRPC/HTTP 边界统一捕获后做两件事：
 * ①上报 {@link TenantIsolationAuditor} 审计；②对外返回「不存在」，
 * 避免通过错误码差异枚举其他租户的资源是否存在。</p>
 */
public class TenantAccessDeniedException extends RuntimeException {

    private final transient IsolationViolation violation;

    public TenantAccessDeniedException(IsolationViolation violation) {
        super("跨租户访问被拒：" + violation.resourceType() + ":" + violation.resourceId());
        this.violation = violation;
    }

    /** 越权事实（供审计上报）。 */
    public IsolationViolation violation() {
        return violation;
    }
}
