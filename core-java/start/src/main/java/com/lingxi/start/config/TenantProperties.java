package com.lingxi.start.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 租户隔离配置（M5-5.5）：并发资源组配额与租户守卫豁免名单，全部环境变量化。
 */
@Component
@ConfigurationProperties(prefix = "lingxi.tenant")
public class TenantProperties {

    /** 未单独配置的租户默认并发上限（大租户通过 {@link #quotas} 单独提额）。 */
    private int defaultMaxConcurrent = 64;

    /** 租户闸门对象上限：超出后长尾租户共用兜底闸门，防止租户号滥用导致内存膨胀。 */
    private int maxGates = 5000;

    /** 大租户独立配额：租户 ID → 并发上限（未配置者用默认值）。 */
    private Map<String, Integer> quotas = new HashMap<>();

    /**
     * 租户守卫豁免的服务名（无租户语义的跨语言探测）。
     * 默认豁免健康检查与业务工具（后者由 AI 域调用，租户随请求字段传递）。
     */
    private List<String> exemptServices = List.of(
            "lingxi.health.v1.HealthService",
            "lingxi.business.v1.BusinessToolService");

    public int getDefaultMaxConcurrent() {
        return defaultMaxConcurrent;
    }

    public void setDefaultMaxConcurrent(int defaultMaxConcurrent) {
        this.defaultMaxConcurrent = defaultMaxConcurrent;
    }

    public int getMaxGates() {
        return maxGates;
    }

    public void setMaxGates(int maxGates) {
        this.maxGates = maxGates;
    }

    public Map<String, Integer> getQuotas() {
        return quotas;
    }

    public void setQuotas(Map<String, Integer> quotas) {
        this.quotas = quotas;
    }

    public List<String> getExemptServices() {
        return exemptServices;
    }

    public void setExemptServices(List<String> exemptServices) {
        this.exemptServices = exemptServices;
    }
}
