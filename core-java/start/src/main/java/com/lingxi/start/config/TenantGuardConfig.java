package com.lingxi.start.config;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lingxi.common.tenant.TenantGuardInterceptor;
import com.lingxi.common.tenant.TenantQuotaGate;

/**
 * 租户隔离装配（M5-5.5）：租户并发资源组与 gRPC 租户守卫拦截器。
 *
 * <p>数据隔离审计端口（{@link TenantIsolationAuditor}）由 log 域的
 * {@code OperationLogIsolationAuditor} 提供实现（M5-5.6：日志 + 落库），此处不再装配默认实现。</p>
 */
@Configuration
public class TenantGuardConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantGuardConfig.class);

    /** 租户级并发闸门：每个租户独占槽位，大租户按配置单独提额。 */
    @Bean
    public TenantQuotaGate tenantQuotaGate(TenantProperties properties) {
        log.info("租户资源组就绪：默认并发={} 闸门上限={} 独立配额租户数={}",
                properties.getDefaultMaxConcurrent(), properties.getMaxGates(),
                properties.getQuotas().size());
        return new TenantQuotaGate(properties.getDefaultMaxConcurrent(),
                properties.getQuotas(), properties.getMaxGates());
    }

    /** gRPC 租户守卫：可信租户注入 + 强制校验 + 并发配额。 */
    @Bean
    public TenantGuardInterceptor tenantGuardInterceptor(TenantProperties properties,
                                                        TenantQuotaGate tenantQuotaGate) {
        Set<String> exemptServices = Set.copyOf(properties.getExemptServices());
        log.info("租户守卫就绪：豁免服务={}", exemptServices);
        return new TenantGuardInterceptor(exemptServices, tenantQuotaGate);
    }
}
