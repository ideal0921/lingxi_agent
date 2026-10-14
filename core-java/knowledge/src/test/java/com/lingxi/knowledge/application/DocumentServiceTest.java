package com.lingxi.knowledge.application;

import com.lingxi.common.exception.BizException;
import com.lingxi.knowledge.domain.Document;
import com.lingxi.knowledge.domain.DocumentStatus;
import com.lingxi.knowledge.domain.KbStatus;
import com.lingxi.knowledge.domain.KnowledgeBase;
import com.lingxi.knowledge.domain.UploadSession;
import com.lingxi.knowledge.domain.event.KnowledgeEventPublisher;
import com.lingxi.knowledge.domain.repo.DocumentRepository;
import com.lingxi.knowledge.domain.repo.KnowledgeBaseRepository;
import com.lingxi.knowledge.domain.repo.UploadSessionRepository;
import com.lingxi.knowledge.domain.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档用例编排测试：上传校验、去重、版本管理、分片合并与状态回写（全内存替身，不连外部依赖）。 */
class DocumentServiceTest {

    private static final String TENANT = "t1";
    private static final long MAX_SIZE = 1024 * 1024;

    @TempDir
    Path tempDir;

    private InMemoryKnowledgeBaseRepository kbRepo;
    private InMemoryDocumentRepository docRepo;
    private InMemoryUploadSessionRepository uploadRepo;
    private InMemoryObjectStorage storage;
    private RecordingPublisher publisher;
    private DocumentService service;
    private String kbId;

    @BeforeEach
    void setUp() {
        kbRepo = new InMemoryKnowledgeBaseRepository();
        docRepo = new InMemoryDocumentRepository();
        uploadRepo = new InMemoryUploadSessionRepository();
        storage = new InMemoryObjectStorage();
        publisher = new RecordingPublisher();
        service = new DocumentService(kbRepo, docRepo, uploadRepo, storage, publisher,
                tempDir.toString(), 3600, 4, MAX_SIZE);
        kbId = kbRepo.save(new KnowledgeBase(TENANT, "售后知识库", "")).getKbId();
    }

    private Document upload(String content, String docId) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return service.upload(kbId, TENANT, docId, "退款政策", "c1", "售后,退款",
                "policy.md", "text/markdown", new ByteArrayInputStream(bytes), bytes.length);
    }

    @Test
    void uploadCreatesPendingDocumentAndPublishesIngest() {
        Document document = upload("# 退款\n1-3 个工作日", null);

        assertEquals(DocumentStatus.PENDING, document.getStatus());
        assertEquals(1, document.getVersion());
        assertEquals("markdown", document.getDocType());
        assertEquals("售后,退款", document.getTags());
        assertNotNull(storage.objects.get(document.getObjectKey()));
        assertEquals(1, publisher.events.size());
        assertEquals(document.getDocId(), publisher.events.get(0).docId());
        assertTrue(publisher.events.get(0).fileUrl().startsWith("memory://"));
    }

    @Test
    void duplicateContentIsRejected() {
        upload("相同内容", null);

        BizException error = assertThrows(BizException.class, () -> upload("相同内容", null));
        assertEquals(40902, error.getCode());
    }

    @Test
    void newVersionBumpsVersionAndResetsStatus() {
        Document first = upload("第一版内容", null);
        docRepo.save(markIndexed(first.getDocId(), 5, 2));

        Document second = upload("第二版内容", first.getDocId());

        assertEquals(first.getDocId(), second.getDocId());
        assertEquals(2, second.getVersion());
        assertEquals(DocumentStatus.PENDING, second.getStatus());
        assertEquals(0, second.getChildCount());
        assertEquals(2, publisher.events.size());
        assertEquals(2, publisher.events.get(1).version());
    }

    @Test
    void sameContentNewVersionIsRejected() {
        Document first = upload("内容不变", null);

        BizException error = assertThrows(BizException.class, () -> upload("内容不变", first.getDocId()));
        assertEquals(40903, error.getCode());
    }

    @Test
    void chunkedUploadConcatenatesPartsInOrder() {
        String content = "分片内容-ABCDEFGHIJ";
        byte[] first = content.substring(0, 6).getBytes(StandardCharsets.UTF_8);
        byte[] second = content.substring(6).getBytes(StandardCharsets.UTF_8);
        UploadSession session = service.initUpload(kbId, TENANT, null, "大文件", "c1", "", "big.txt", 2);

        service.uploadPart(session.getUploadId(), 2, new ByteArrayInputStream(second), second.length);
        UploadSession afterOne = service.uploadPart(session.getUploadId(), 1, new ByteArrayInputStream(first), first.length);
        assertEquals(2, afterOne.getReceivedParts().size());

        Document document = service.completeUpload(session.getUploadId());

        assertEquals(DocumentStatus.PENDING, document.getStatus());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, document.getSizeBytes());
        // 与原文 SHA-256 一致（证明分片按序号正确拼接，未错位/未丢片）
        assertEquals(sha256(content), document.getContentHash());
        assertEquals(UploadSession.UploadStatus.COMPLETED,
                uploadRepo.findById(session.getUploadId()).orElseThrow().getStatus());
    }

    @Test
    void completeRejectsIncompleteParts() {
        UploadSession session = service.initUpload(kbId, TENANT, null, "大文件", "c1", "", "big.txt", 3);
        byte[] part = "abc".getBytes(StandardCharsets.UTF_8);
        service.uploadPart(session.getUploadId(), 1, new ByteArrayInputStream(part), part.length);

        BizException error = assertThrows(BizException.class,
                () -> service.completeUpload(session.getUploadId()));
        assertEquals(40003, error.getCode());
    }

    @Test
    void applyIndexedResultWritesBackCurrentVersionOnly() {
        Document document = upload("回写内容", null);

        service.applyIndexedResult(document.getDocId(), TENANT, 1, "INDEXED", 7, 3, "");
        Document indexed = docRepo.findById(document.getDocId()).orElseThrow();
        assertEquals(DocumentStatus.INDEXED, indexed.getStatus());
        assertEquals(7, indexed.getChildCount());

        // 过期版本回写应被忽略（避免旧版本事件覆盖新版本状态）
        upload("回写内容-第二版", document.getDocId());
        service.applyIndexedResult(document.getDocId(), TENANT, 1, "FAILED", 0, 0, "旧事件");
        assertEquals(DocumentStatus.PENDING, docRepo.findById(document.getDocId()).orElseThrow().getStatus());
    }

    @Test
    void rejectsTenantMismatchAndDisabledKb() {
        BizException mismatch = assertThrows(BizException.class,
                () -> service.listDocuments(kbId, "other-tenant", null));
        assertEquals(40301, mismatch.getCode());

        kbRepo.save(new KnowledgeBase(kbId, TENANT, "停用库", "", KbStatus.DISABLED,
                System.currentTimeMillis(), System.currentTimeMillis()));
        BizException disabled = assertThrows(BizException.class, () -> upload("内容", null));
        assertEquals(40302, disabled.getCode());
    }

    @Test
    void rejectsOversizedSingleUpload() {
        byte[] bytes = new byte[(int) MAX_SIZE + 1];
        BizException error = assertThrows(BizException.class, () -> service.upload(kbId, TENANT, null,
                "超大", "", "", "huge.bin", "application/octet-stream",
                new ByteArrayInputStream(bytes), bytes.length));
        assertEquals(40002, error.getCode());
    }

    @Test
    void deleteMarksDeletedRemovesObjectPublishesEventAndReleasesHashSlot() {
        Document document = upload("待下线内容", null);
        String objectKey = document.getObjectKey();
        assertTrue(storage.objects.containsKey(objectKey));

        Document deleted = service.deleteDocument(document.getDocId(), TENANT);

        assertEquals(DocumentStatus.DELETED, deleted.getStatus());
        assertFalse(storage.objects.containsKey(objectKey));        // 原件已物理清理
        assertEquals(1, publisher.deletes.size());                   // 下线事件已投递
        assertEquals(document.getDocId(), publisher.deletes.get(0).docId());

        // 哈希唯一槽位已释放：同一内容可重新上传（不再被判重复）
        Document reUploaded = upload("待下线内容", null);
        assertNotNull(reUploaded.getDocId());
        assertEquals(DocumentStatus.PENDING, reUploaded.getStatus());
    }

    @Test
    void repeatDeleteIsRejected() {
        Document document = upload("内容", null);
        service.deleteDocument(document.getDocId(), TENANT);

        BizException error = assertThrows(BizException.class,
                () -> service.deleteDocument(document.getDocId(), TENANT));
        assertEquals(40402, error.getCode());
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Document markIndexed(String docId, int childCount, int parentCount) {
        Document document = docRepo.findById(docId).orElseThrow();
        document.markIndexed(childCount, parentCount);
        return document;
    }

    // ---------------- 内存替身 ----------------

    private static final class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {
        private final Map<String, KnowledgeBase> store = new HashMap<>();

        @Override
        public KnowledgeBase save(KnowledgeBase kb) {
            store.put(kb.getKbId(), kb);
            return kb;
        }

        @Override
        public Optional<KnowledgeBase> findById(String kbId) {
            return Optional.ofNullable(store.get(kbId));
        }

        @Override
        public List<KnowledgeBase> listByTenant(String tenantId) {
            return store.values().stream().filter(kb -> kb.getTenantId().equals(tenantId)).toList();
        }

        @Override
        public void delete(String kbId) {
            store.remove(kbId);
        }
    }

    private static final class InMemoryDocumentRepository implements DocumentRepository {
        private final Map<String, Document> store = new HashMap<>();

        @Override
        public Document save(Document document) {
            store.put(document.getDocId(), document);
            return document;
        }

        @Override
        public Optional<Document> findById(String docId) {
            return Optional.ofNullable(store.get(docId));
        }

        @Override
        public Optional<Document> findByContentHash(String kbId, String contentHash) {
            return store.values().stream()
                    .filter(doc -> doc.getKbId().equals(kbId) && doc.getContentHash().equals(contentHash))
                    .findFirst();
        }

        @Override
        public List<Document> listByKb(String kbId, String tenantId, DocumentStatus status) {
            return store.values().stream()
                    .filter(doc -> doc.getKbId().equals(kbId) && doc.getTenantId().equals(tenantId))
                    .filter(doc -> status == null || doc.getStatus() == status)
                    .collect(Collectors.toList());
        }

        @Override
        public void delete(String docId) {
            store.remove(docId);
        }
    }

    private static final class InMemoryUploadSessionRepository implements UploadSessionRepository {
        private final Map<String, UploadSession> store = new HashMap<>();

        @Override
        public UploadSession save(UploadSession session) {
            store.put(session.getUploadId(), session);
            return session;
        }

        @Override
        public Optional<UploadSession> findById(String uploadId) {
            return Optional.ofNullable(store.get(uploadId));
        }

        @Override
        public void delete(String uploadId) {
            store.remove(uploadId);
        }
    }

    private static final class InMemoryObjectStorage implements ObjectStorage {
        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public void put(String objectKey, InputStream stream, long sizeBytes, String contentType) {
            try {
                objects.put(objectKey, stream.readAllBytes());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String presignedGetUrl(String objectKey, int expirySeconds) {
            return "memory://" + objectKey;
        }

        @Override
        public InputStream get(String objectKey) {
            byte[] content = objects.get(objectKey);
            assertNotNull(content, "对象不存在: " + objectKey);
            return new ByteArrayInputStream(content);
        }

        @Override
        public void remove(String objectKey) {
            objects.remove(objectKey);
        }
    }

    private static final class RecordingPublisher implements KnowledgeEventPublisher {
        private final List<IngestEvent> events = new ArrayList<>();
        private final List<DeleteEvent> deletes = new ArrayList<>();

        @Override
        public void publishIngest(String docId, String tenantId, String kbId, int version,
                                  String fileName, String fileUrl) {
            events.add(new IngestEvent(docId, tenantId, kbId, version, fileName, fileUrl));
        }

        @Override
        public void publishDelete(String docId, String tenantId, String kbId) {
            deletes.add(new DeleteEvent(docId, tenantId, kbId));
        }
    }

    private record IngestEvent(String docId, String tenantId, String kbId, int version,
                               String fileName, String fileUrl) {
    }

    private record DeleteEvent(String docId, String tenantId, String kbId) {
    }
}
