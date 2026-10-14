package com.lingxi.knowledge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 知识库聚合校验与启停测试。 */
class KnowledgeBaseTest {

    @Test
    void createsEnabledByDefault() {
        KnowledgeBase kb = new KnowledgeBase("t1", "售后知识库", "售后政策与流程");

        assertEquals(KbStatus.ENABLED, kb.getStatus());
        assertEquals("t1", kb.getTenantId());
        assertEquals("", new KnowledgeBase("t1", "空描述库", null).getDescription());
    }

    @Test
    void rejectsBlankTenantAndName() {
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeBase(" ", "名称", null));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeBase("t1", "  ", null));
    }

    @Test
    void rejectsOverlongNameAndDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeBase("t1", "x".repeat(129), null));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeBase("t1", "名称", "x".repeat(1001)));
    }

    @Test
    void updateInfoAndStatus() {
        KnowledgeBase kb = new KnowledgeBase("t1", "旧名", "旧描述");

        kb.updateInfo("新名", "新描述");
        kb.changeStatus(KbStatus.DISABLED);

        assertEquals("新名", kb.getName());
        assertEquals("新描述", kb.getDescription());
        assertEquals(KbStatus.DISABLED, kb.getStatus());
    }

    @Test
    void rejectsNullStatus() {
        KnowledgeBase kb = new KnowledgeBase("t1", "名称", null);
        assertThrows(IllegalArgumentException.class, () -> kb.changeStatus(null));
    }
}
