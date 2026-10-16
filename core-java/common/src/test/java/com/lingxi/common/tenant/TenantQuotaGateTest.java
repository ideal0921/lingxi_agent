package com.lingxi.common.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 租户并发闸门单测（M5-5.5 大租户独立资源组）：
 * 配额生效、租户间互不挤占、闸门对象数量封顶。
 */
class TenantQuotaGateTest {

    @Test
    void acquireAndReleaseRespectQuota() {
        TenantQuotaGate gate = new TenantQuotaGate(2, Map.of(), 100);

        assertTrue(gate.tryAcquire("t1"));
        assertTrue(gate.tryAcquire("t1"));
        assertFalse(gate.tryAcquire("t1"), "超过默认配额应拒绝（不排队）");
        assertEquals(2, gate.inFlightOf("t1"));

        gate.release("t1");
        assertTrue(gate.tryAcquire("t1"), "释放后应可再次占用");
    }

    @Test
    void bigTenantGetsIndependentHigherQuota() {
        TenantQuotaGate gate = new TenantQuotaGate(1, Map.of("big", 3), 100);

        // 小租户占满自身配额后失效，但不影响大租户
        assertTrue(gate.tryAcquire("small"));
        assertFalse(gate.tryAcquire("small"));

        assertEquals(1, gate.quotaOf("small"));
        assertEquals(3, gate.quotaOf("big"), "大租户应获得独立配额");
        assertTrue(gate.tryAcquire("big"));
        assertTrue(gate.tryAcquire("big"));
        assertTrue(gate.tryAcquire("big"));
        assertFalse(gate.tryAcquire("big"), "大租户超额同样被拒");
    }

    @Test
    void duplicateReleaseDoesNotOverdraw() {
        TenantQuotaGate gate = new TenantQuotaGate(1, Map.of(), 100);
        gate.tryAcquire("t1");
        gate.release("t1");
        gate.release("t1");
        gate.release("t1");

        // 重复释放不得透支信号量（否则配额可被刷大）
        assertTrue(gate.tryAcquire("t1"));
        assertFalse(gate.tryAcquire("t1"));
        assertEquals(1, gate.inFlightOf("t1"));
    }

    @Test
    void gateCountIsCappedForLongTailTenants() {
        TenantQuotaGate gate = new TenantQuotaGate(1, Map.of(), 3);

        for (int i = 0; i < 50; i++) {
            gate.tryAcquire("long-tail-" + i);
        }

        assertTrue(gate.gateCount() <= 3, "闸门对象数量应封顶，防租户号滥用导致内存膨胀");
        assertEquals(1, gate.quotaOf("long-tail-49"), "超限租户共用兜底闸门仍受默认配额保护");
    }

    @Test
    void blankTenantSharesSingleGate() {
        TenantQuotaGate gate = new TenantQuotaGate(1, Map.of(), 100);
        assertNotNull(gate);
        assertTrue(gate.tryAcquire(null));
        assertFalse(gate.tryAcquire(null), "空租户归一到同一闸门");
        assertEquals(1, gate.gateCount());
    }
}
