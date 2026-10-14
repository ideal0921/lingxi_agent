package com.lingxi.knowledge.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分片上传会话测试：分片记录幂等、齐全判定与状态约束。 */
class UploadSessionTest {

    private static UploadSession session() {
        return new UploadSession("t1", "kb1", "", "标题", "big.pdf", "", "", 3, 5242880);
    }

    @Test
    void recordsPartsIdempotently() {
        UploadSession session = session();

        session.markPartReceived(2);
        session.markPartReceived(2);  // 断点续传重传同一片
        session.markPartReceived(1);

        assertEquals(2, session.getReceivedParts().size());
        assertFalse(session.allPartsReceived());
    }

    @Test
    void completeRequiresAllParts() {
        UploadSession session = session();
        session.markPartReceived(1);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, session::complete);
        assertTrue(error.getMessage().contains("分片不完整"));
    }

    @Test
    void completeMarksCompletedWhenAllReceived() {
        UploadSession session = session();
        for (int part = 1; part <= 3; part++) {
            session.markPartReceived(part);
        }

        session.complete();

        assertEquals(UploadSession.UploadStatus.COMPLETED, session.getStatus());
        assertFalse(session.isOpen());
    }

    @Test
    void rejectsOutOfRangeAndAfterClose() {
        UploadSession session = session();
        assertThrows(IllegalArgumentException.class, () -> session.markPartReceived(0));
        assertThrows(IllegalArgumentException.class, () -> session.markPartReceived(4));

        session.abort();
        assertThrows(IllegalStateException.class, () -> session.markPartReceived(1));
        assertThrows(IllegalStateException.class, session::complete);
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new UploadSession("t1", "kb1", "", "t", "f.pdf", "", "", 0, 5242880));
        assertThrows(IllegalArgumentException.class,
                () -> new UploadSession("t1", "kb1", "", "t", "f.pdf", "", "", 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new UploadSession("t1", "kb1", "", "t", " ", "", "", 1, 1024));
    }
}
