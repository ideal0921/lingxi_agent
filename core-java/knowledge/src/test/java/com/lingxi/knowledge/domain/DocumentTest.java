package com.lingxi.knowledge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档切片状态机、版本管理与标签规范化测试。 */
class DocumentTest {

    private static Document newDocument() {
        return new Document("doc-1", "kb1", "t1", "c1", "标题", "policy.md",
                "markdown", "a".repeat(64), 10L, "tenant/t1/kb/kb1/doc/doc-1/v1/policy.md", " 售后, 退款 ,售后 ");
    }

    @Test
    void tagsAreNormalizedAndDeduplicated() {
        // 去空白、去重后按原顺序保留
        assertEquals("售后,退款", newDocument().getTags());
    }

    @Test
    void initialStatusIsPending() {
        Document document = newDocument();
        assertEquals(DocumentStatus.PENDING, document.getStatus());
        assertEquals(1, document.getVersion());
    }

    @Test
    void indexedAndFailedTransitions() {
        Document document = newDocument();

        assertTrue(document.markIndexed(7, 3));
        assertEquals(DocumentStatus.INDEXED, document.getStatus());
        assertEquals(7, document.getChildCount());
        assertEquals(3, document.getParentCount());

        assertTrue(document.markFailed("Milvus 不可达"));
        assertEquals(DocumentStatus.FAILED, document.getStatus());
        assertEquals("Milvus 不可达", document.getStatusMessage());
    }

    @Test
    void newVersionResetsStatusAndCounts() {
        Document document = newDocument();
        document.markIndexed(5, 2);

        assertTrue(document.applyNewVersion(2, "b".repeat(64), 20L, "tenant/t1/kb/kb1/doc/doc-1/v2/policy.md"));

        assertEquals(2, document.getVersion());
        assertEquals(DocumentStatus.PENDING, document.getStatus());
        assertEquals(0, document.getChildCount());
        assertEquals(0, document.getParentCount());
        assertEquals("", document.getStatusMessage());
    }

    @Test
    void deletedIsTerminal() {
        Document document = newDocument();
        assertTrue(document.markDeleted());
        assertEquals(DocumentStatus.DELETED, document.getStatus());

        // 终态不可再迁移（重复下线/回写均被拒绝）
        assertFalse(document.markDeleted());
        assertFalse(document.markIndexed(1, 1));
        assertFalse(document.applyNewVersion(2, "c".repeat(64), 1L, "k2"));
    }

    @Test
    void rejectsInvalidHash() {
        assertThrows(IllegalArgumentException.class,
                () -> new Document("kb1", "t1", "", "标题", "policy.md", "markdown",
                        "短哈希", 10L, "key", ""));
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new Document(" ", "t1", "", "标题", "policy.md", "markdown", "a".repeat(64), 1L, "key", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new Document("kb1", "t1", "", " ", "policy.md", "markdown", "a".repeat(64), 1L, "key", ""));
    }
}
