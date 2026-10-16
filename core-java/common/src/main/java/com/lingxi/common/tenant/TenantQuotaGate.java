package com.lingxi.common.tenant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户级并发闸门（M5-5.5 大租户独立资源组）。
 *
 * <p>每个租户一个独立信号量：租户只能占用自身配额，无法挤占其他租户的并发槽位，
 * 从而避免「单租户打满共享连接池/线程池导致全站劣化」。大租户通过配置获得
 * 独立且更高的配额（见 {@code lingxi.tenant.quotas}）。</p>
 *
 * <p>策略：闸门满时**立即拒绝、不排队**（与坐席快捷回复同步通道一致），
 * 由调用方降级，避免排队放大尾延迟。</p>
 *
 * <p>内存控制：租户闸门按需创建，总量以 {@code maxGates} 封顶；
 * 超出上限的长尾租户共用兜底闸门（并发上限 = 默认配额），防止租户号被滥用导致内存膨胀。</p>
 */
public class TenantQuotaGate {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaGate.class);

    private final int defaultMaxConcurrent;
    private final Map<String, Integer> tenantQuotas;
    private final int maxGates;

    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    /** 长尾租户兜底闸门（超出 maxGates 后共用，仍受默认配额保护）。 */
    private final Gate fallbackGate;

    public TenantQuotaGate(int defaultMaxConcurrent, Map<String, Integer> tenantQuotas, int maxGates) {
        this.defaultMaxConcurrent = Math.max(1, defaultMaxConcurrent);
        this.tenantQuotas = tenantQuotas == null ? Map.of() : Map.copyOf(tenantQuotas);
        this.maxGates = Math.max(1, maxGates);
        this.fallbackGate = new Gate(this.defaultMaxConcurrent);
    }

    /**
     * 尝试占用一个并发槽位（非阻塞）。
     *
     * @param tenantId 租户标识
     * @return true 表示已占用（调用方**必须**在流程结束时 {@link #release(String)}）
     */
    public boolean tryAcquire(String tenantId) {
        Gate gate = gateOf(tenantId);
        boolean acquired = gate.tryAcquire();
        if (!acquired) {
            log.warn("租户并发已达配额 tenant={} quota={} inFlight={}", tenantId, gate.quota(), gate.inFlight());
        }
        return acquired;
    }

    /** 释放并发槽位（与 {@link #tryAcquire(String)} 配对；重复释放不会透支）。 */
    public void release(String tenantId) {
        gateOf(tenantId).release();
    }

    /** 指定租户的有效配额（含大租户独立配额）。 */
    public int quotaOf(String tenantId) {
        return gateOf(tenantId).quota();
    }

    /** 指定租户当前在途并发数（观测用）。 */
    public int inFlightOf(String tenantId) {
        return gateOf(tenantId).inFlight();
    }

    /** 已创建的租户闸门数量（用于验证内存封顶生效）。 */
    public int gateCount() {
        return gates.size();
    }

    private Gate gateOf(String tenantId) {
        String key = tenantId == null || tenantId.isBlank() ? "" : tenantId;
        Gate existing = gates.get(key);
        if (existing != null) {
            return existing;
        }
        if (gates.size() >= maxGates) {
            // 长尾租户共用兜底闸门：仍受默认配额保护，且不再新增对象
            return fallbackGate;
        }
        int quota = tenantQuotas.getOrDefault(key, defaultMaxConcurrent);
        return gates.computeIfAbsent(key, k -> new Gate(quota));
    }

    /** 验证属性：单租户信号量（配额 + 在途计数）。 */
    private static final class Gate {
        private final Semaphore semaphore;
        private final int quota;
        private final AtomicInteger inFlight = new AtomicInteger();

        private Gate(int quota) {
            this.quota = Math.max(1, quota);
            this.semaphore = new Semaphore(this.quota);
        }

        private boolean tryAcquire() {
            if (!semaphore.tryAcquire()) {
                return false;
            }
            inFlight.incrementAndGet();
            return true;
        }

        private void release() {
            if (inFlight.get() <= 0) {
                log.warn("租户并发槽位重复释放已忽略");
                return;
            }
            inFlight.decrementAndGet();
            semaphore.release();
        }

        private int quota() {
            return quota;
        }

        private int inFlight() {
            return inFlight.get();
        }
    }
}
