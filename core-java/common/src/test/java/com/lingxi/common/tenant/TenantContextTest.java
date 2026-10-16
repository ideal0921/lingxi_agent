package com.lingxi.common.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 租户上下文单测（M5-5.5）：绑定/清理、缺失即拒绝、归属判定。
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void currentIsEmptyWithoutBinding() {
        assertFalse(TenantContext.current().hasTenant(), "未绑定时应为空身份");
        assertEquals("", TenantContext.currentTenantId());
    }

    @Test
    void requireTenantIdRejectsMissingContext() {
        assertThrows(MissingTenantContextException.class, TenantContext::requireTenantId,
                "缺少租户上下文必须显式抛错（不得回落默认租户）");
    }

    @Test
    void setAndClearBindsCurrentThreadOnly() {
        TenantContext.set(new TenantIdentity("tenant-a", "u1", "agent"));
        assertEquals("tenant-a", TenantContext.requireTenantId());
        assertTrue(TenantContext.belongsTo("tenant-a"));
        assertFalse(TenantContext.belongsTo("tenant-b"));

        TenantContext.clear();
        assertThrows(MissingTenantContextException.class, TenantContext::requireTenantId,
                "清理后应恢复未绑定状态（防线程复用串号）");
    }

    @Test
    void identityNormalizesBlankValues() {
        TenantIdentity identity = new TenantIdentity("  tenant-a  ", null, " agent ");
        assertEquals("tenant-a", identity.tenantId(), "租户 ID 应去空白");
        assertEquals("", identity.userId(), "null 用户应归一为空串");
        assertEquals("agent", identity.role());
        assertTrue(identity.hasTenant());
    }
}
