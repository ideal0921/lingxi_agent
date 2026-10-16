package com.lingxi.log.audit;

import org.springframework.stereotype.Component;

import com.lingxi.common.tenant.IsolationViolation;
import com.lingxi.common.tenant.LoggingTenantIsolationAuditor;
import com.lingxi.common.tenant.TenantIsolationAuditor;
import com.lingxi.log.application.OperationLogEntry;
import com.lingxi.log.application.OperationLogService;
import com.lingxi.log.domain.OperationResult;

/**
 * 租户隔离审计落库实现（M5-5.6）：把 M5-5.5 的隔离审计端口从「仅日志」升级为「日志 + 落库」，
 * 使越权尝试可在管理后台日志模块中检索。
 *
 * <p>组合而非替换：仍保留结构化 WARN 日志（便于日志平台建立实时告警），
 * 落库失败由 {@link OperationLogService} 内部兜底，不影响主链路。</p>
 */
@Component
public class OperationLogIsolationAuditor implements TenantIsolationAuditor {

    /** 审计模块与动作常量（与日志页筛选项对应）。 */
    public static final String MODULE_SECURITY = "security";
    public static final String ACTION_ISOLATION_DENIED = "isolation_denied";

    private final OperationLogService operationLogService;
    private final TenantIsolationAuditor loggingAuditor = new LoggingTenantIsolationAuditor();

    public OperationLogIsolationAuditor(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Override
    public void record(IsolationViolation violation) {
        if (violation == null) {
            return;
        }
        loggingAuditor.record(violation);
        operationLogService.record(OperationLogEntry.of(
                violation.tenantId(), violation.principal(), "", violation.role(),
                MODULE_SECURITY, ACTION_ISOLATION_DENIED,
                violation.resourceType(), violation.resourceId(),
                OperationResult.DENIED,
                violation.detail() + "（资源归属租户=" + violation.resourceTenantId() + "）",
                0L));
    }
}
