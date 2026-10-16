package com.lingxi.common.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据隔离审计默认实现（M5-5.5）：结构化 WARN 日志，供日志检索（Elasticsearch）告警规则命中。
 *
 * <p>日志前缀固定为 {@code [tenant-isolation-denied]}，便于 Loki/ES 建立越权告警。</p>
 */
public class LoggingTenantIsolationAuditor implements TenantIsolationAuditor {

    private static final Logger log = LoggerFactory.getLogger(LoggingTenantIsolationAuditor.class);

    @Override
    public void record(IsolationViolation violation) {
        if (violation == null) {
            return;
        }
        log.warn("[tenant-isolation-denied] tenant={} principal={} role={} resource={}:{} resourceTenant={} action={} detail={}",
                violation.tenantId(), violation.principal(), violation.role(),
                violation.resourceType(), violation.resourceId(), violation.resourceTenantId(),
                violation.action(), violation.detail());
    }
}
