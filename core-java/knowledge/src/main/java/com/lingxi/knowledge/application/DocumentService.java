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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 文档用例编排：上传校验 → 去重 → 版本管理 → 落对象存储 → 投递入库事件。
 *
 * <p><b>超大文档</b>：单请求上传受 {@code max-file-size-bytes} 限制；超出上限走
 * 「初始化 → 逐片上传 → 合并」分片通道，分片暂存对象存储，合并时按序拼接并计算
 * SHA-256，避免内存整文件驻留。</p>
 *
 * <p><b>去重</b>：以内容 SHA-256 在知识库内识别重复上传，命中即拒绝（避免重复切片）。</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final int ERR_FILE_EMPTY = 40001;
    private static final int ERR_FILE_TOO_LARGE = 40002;
    private static final int ERR_PARTS_INCOMPLETE = 40003;
    private static final int ERR_KB_NOT_FOUND = 40401;
    private static final int ERR_DOC_NOT_FOUND = 40402;
    private static final int ERR_UPLOAD_NOT_FOUND = 40403;
    private static final int ERR_TENANT_MISMATCH = 40301;
    private static final int ERR_KB_DISABLED = 40302;
    private static final int ERR_DUPLICATE = 40902;
    private static final int ERR_NO_CHANGE = 40903;
    private static final int ERR_STORAGE = 50001;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final ObjectStorage objectStorage;
    private final KnowledgeEventPublisher eventPublisher;
    private final Path tmpDir;
    private final int presignExpirySeconds;
    private final int partSizeBytes;
    private final long maxFileSizeBytes;

    public DocumentService(KnowledgeBaseRepository knowledgeBaseRepository,
                           DocumentRepository documentRepository,
                           UploadSessionRepository uploadSessionRepository,
                           ObjectStorage objectStorage,
                           KnowledgeEventPublisher eventPublisher,
                           @Value("${lingxi.kb.upload.tmp-dir}") String tmpDir,
                           @Value("${lingxi.kb.minio.presign-expiry-seconds:3600}") int presignExpirySeconds,
                           @Value("${lingxi.kb.upload.part-size-bytes:5242880}") int partSizeBytes,
                           @Value("${lingxi.kb.upload.max-file-size-bytes:524288000}") long maxFileSizeBytes) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.objectStorage = objectStorage;
        this.eventPublisher = eventPublisher;
        this.tmpDir = Path.of(tmpDir);
        this.presignExpirySeconds = presignExpirySeconds;
        this.partSizeBytes = partSizeBytes;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    /** 单请求上传（小文件）：暂存并计算哈希后落库与投递入库事件。 */
    public Document upload(String kbId, String tenantId, String docId, String title, String categoryId,
                           String tags, String fileName, String contentType,
                           InputStream content, long sizeBytes) {
        requireKb(kbId, tenantId);
        if (sizeBytes <= 0) {
            throw new BizException(ERR_FILE_EMPTY, "上传文件为空");
        }
        if (sizeBytes > maxFileSizeBytes) {
            throw new BizException(ERR_FILE_TOO_LARGE,
                    "文件超过单请求上限 " + maxFileSizeBytes + " 字节，请使用分片上传");
        }
        Staged staged = stage(content);
        try {
            return persist(kbId, tenantId, docId, title, categoryId, tags, fileName, contentType, staged);
        } finally {
            deleteQuietly(staged.path());
        }
    }

    /** 初始化分片上传会话，返回 uploadId 与分片建议大小。 */
    public UploadSession initUpload(String kbId, String tenantId, String docId, String title,
                                    String categoryId, String tags, String fileName, int totalParts) {
        requireKb(kbId, tenantId);
        UploadSession session = new UploadSession(tenantId, kbId, docId, title, fileName,
                categoryId, tags, totalParts, partSizeBytes);
        return uploadSessionRepository.save(session);
    }

    /** 上传单个分片（幂等：断点续传可重传同一片）。 */
    public UploadSession uploadPart(String uploadId, int partNumber, InputStream content, long sizeBytes) {
        UploadSession session = requireOpenSession(uploadId);
        if (sizeBytes <= 0) {
            throw new BizException(ERR_FILE_EMPTY, "分片内容为空");
        }
        try {
            objectStorage.put(partKey(uploadId, partNumber), content, sizeBytes, "application/octet-stream");
        } catch (RuntimeException e) {
            throw new BizException(ERR_STORAGE, "分片上传失败：" + e.getMessage());
        }
        session.markPartReceived(partNumber);
        return uploadSessionRepository.save(session);
    }

    /** 合并分片并落库（分片齐全为前提），随后投递入库事件。 */
    public Document completeUpload(String uploadId) {
        UploadSession session = requireOpenSession(uploadId);
        if (!session.allPartsReceived()) {
            throw new BizException(ERR_PARTS_INCOMPLETE,
                    "分片不完整，已收 " + session.getReceivedParts().size() + "/" + session.getTotalParts());
        }
        Staged staged = concatParts(session);
        try {
            Document document = persist(session.getKbId(), session.getTenantId(), session.getDocId(),
                    session.getTitle(), session.getCategoryId(), session.getTags(),
                    session.getFileName(), null, staged);
            session.complete();
            uploadSessionRepository.save(session);
            return document;
        } finally {
            deleteQuietly(staged.path());
            removeParts(uploadId, session.getTotalParts());
        }
    }

    /** 中止上传会话并清理已上传分片。 */
    public void abortUpload(String uploadId) {
        UploadSession session = requireOpenSession(uploadId);
        session.abort();
        uploadSessionRepository.save(session);
        removeParts(uploadId, session.getTotalParts());
    }

    /** 列出文档（可按状态过滤）。 */
    public List<Document> listDocuments(String kbId, String tenantId, DocumentStatus status) {
        requireKb(kbId, tenantId);
        return documentRepository.listByKb(kbId, tenantId, status);
    }

    /** 查询文档（含切片状态）。 */
    public Document getDocument(String docId, String tenantId) {
        return requireDocument(docId, tenantId);
    }

    /**
     * 下线文档：逻辑删除（保留元数据供审计）+ 物理删除原件 + 投递下线事件。
     *
     * <p>向量库切片由 AI 域消费 {@code kb.doc.delete} 异步清理（跨域职责分离）；
     * 同时释放内容哈希唯一槽位，使相同内容后续可重新上传。</p>
     */
    public Document deleteDocument(String docId, String tenantId) {
        Document document = requireDocument(docId, tenantId);
        if (!document.markDeleted()) {
            throw new BizException(ERR_DOC_NOT_FOUND, "文档已下线，无需重复删除");
        }
        document.releaseContentHashSlot();
        Document saved = documentRepository.save(document);
        // 原件物理清理（对象存储归属知识域，同步删除成本低）
        objectStorage.remove(saved.getObjectKey());
        eventPublisher.publishDelete(saved.getDocId(), saved.getTenantId(), saved.getKbId());
        return saved;
    }

    /**
     * AI 域入库结果回写：仅当版本号匹配时更新，避免旧版本的迟到回写覆盖新版本状态。
     */
    public void applyIndexedResult(String docId, String tenantId, int version, String status,
                                   int childCount, int parentCount, String message) {
        Document document = documentRepository.findById(docId).orElse(null);
        if (document == null || !document.getTenantId().equals(tenantId)) {
            log.warn("忽略未知文档的入库回写 docId={} tenantId={}", docId, tenantId);
            return;
        }
        if (document.getVersion() != version) {
            log.info("忽略过期版本的入库回写 docId={} 回写版本={} 当前版本={}",
                    docId, version, document.getVersion());
            return;
        }
        boolean updated = "INDEXED".equalsIgnoreCase(status)
                ? document.markIndexed(childCount, parentCount)
                : document.markFailed(message);
        if (updated) {
            documentRepository.save(document);
            log.info("文档状态已回写 docId={} version={} status={}", docId, version, document.getStatus());
        }
    }

    // ---------------- 内部编排 ----------------

    private Document persist(String kbId, String tenantId, String docId, String title, String categoryId,
                             String tags, String fileName, String contentType, Staged staged) {
        String hash = staged.sha256();
        Document existing = docId == null || docId.isBlank() ? null : requireDocument(docId, tenantId);
        int version = existing == null ? 1 : existing.getVersion() + 1;
        String targetDocId = existing == null ? UUID.randomUUID().toString() : existing.getDocId();
        String safeName = sanitizeFileName(fileName);
        String objectKey = buildObjectKey(tenantId, kbId, targetDocId, version, safeName);

        rejectDuplicate(kbId, hash, existing);
        String resolvedTitle = title == null || title.isBlank() ? fileName : title;

        Document document = existing == null
                ? new Document(targetDocId, kbId, tenantId, categoryId, resolvedTitle, safeName,
                        docTypeOf(safeName), hash, staged.size(), objectKey, tags)
                : updatedVersion(existing, version, hash, staged.size(), objectKey);

        try (InputStream in = Files.newInputStream(staged.path())) {
            objectStorage.put(objectKey, in, staged.size(),
                    contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        } catch (IOException e) {
            throw new BizException(ERR_STORAGE, "原始文件落对象存储失败：" + e.getMessage());
        }
        Document saved = documentRepository.save(document);
        publishIngest(saved);
        return saved;
    }

    private Document updatedVersion(Document existing, int version, String hash, long size, String objectKey) {
        if (!existing.applyNewVersion(version, hash, size, objectKey)) {
            throw new BizException(ERR_DOC_NOT_FOUND, "文档已下线，无法上传新版本");
        }
        return existing;
    }

    private void rejectDuplicate(String kbId, String hash, Document existing) {
        Document sameHash = documentRepository.findByContentHash(kbId, hash).orElse(null);
        if (sameHash == null) {
            return;
        }
        if (existing != null && sameHash.getDocId().equals(existing.getDocId())) {
            throw new BizException(ERR_NO_CHANGE, "内容与当前版本一致，无需重新上传");
        }
        throw new BizException(ERR_DUPLICATE, "重复上传：相同内容已存在于知识库（文档 " + sameHash.getDocId() + "）");
    }

    private void publishIngest(Document document) {
        String fileUrl = objectStorage.presignedGetUrl(document.getObjectKey(), presignExpirySeconds);
        eventPublisher.publishIngest(document.getDocId(), document.getTenantId(), document.getKbId(),
                document.getVersion(), document.getFileName(), fileUrl);
    }

    private KnowledgeBase requireKb(String kbId, String tenantId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ERR_KB_NOT_FOUND, "知识库不存在"));
        if (!kb.getTenantId().equals(tenantId)) {
            throw new BizException(ERR_TENANT_MISMATCH, "知识库不属于当前租户");
        }
        if (kb.getStatus() == KbStatus.DISABLED) {
            throw new BizException(ERR_KB_DISABLED, "知识库已停用，禁止变更文档");
        }
        return kb;
    }

    private Document requireDocument(String docId, String tenantId) {
        Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new BizException(ERR_DOC_NOT_FOUND, "文档不存在"));
        if (tenantId != null && !tenantId.isBlank() && !document.getTenantId().equals(tenantId)) {
            throw new BizException(ERR_TENANT_MISMATCH, "文档不属于当前租户");
        }
        return document;
    }

    private UploadSession requireOpenSession(String uploadId) {
        UploadSession session = uploadSessionRepository.findById(uploadId)
                .orElseThrow(() -> new BizException(ERR_UPLOAD_NOT_FOUND, "上传会话不存在"));
        if (!session.isOpen()) {
            throw new BizException(ERR_UPLOAD_NOT_FOUND, "上传会话已结束，状态=" + session.getStatus());
        }
        return session;
    }

    /** 把上传流暂存到本地临时文件并计算 SHA-256（避免整文件驻留内存）。 */
    private Staged stage(InputStream content) {
        Path temp = createTempFile();
        MessageDigest digest = newSha256();
        long size = 0;
        try (OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                size += read;
                if (size > maxFileSizeBytes) {
                    throw new BizException(ERR_FILE_TOO_LARGE, "文件超过上限 " + maxFileSizeBytes + " 字节");
                }
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new BizException(ERR_STORAGE, "文件暂存失败：" + e.getMessage());
        } catch (BizException e) {
            deleteQuietly(temp);
            throw e;
        }
        return new Staged(temp, size, HexFormat.of().formatHex(digest.digest()));
    }

    /** 按序拼接分片对象到临时文件并计算 SHA-256。 */
    private Staged concatParts(UploadSession session) {
        Path temp = createTempFile();
        MessageDigest digest = newSha256();
        long size = 0;
        try (OutputStream out = Files.newOutputStream(temp)) {
            for (int part = 1; part <= session.getTotalParts(); part++) {
                try (InputStream in = objectStorage.get(partKey(session.getUploadId(), part))) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                        size += read;
                    }
                }
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new BizException(ERR_STORAGE, "分片合并失败：" + e.getMessage());
        }
        if (size <= 0) {
            deleteQuietly(temp);
            throw new BizException(ERR_FILE_EMPTY, "分片合并结果为空");
        }
        return new Staged(temp, size, HexFormat.of().formatHex(digest.digest()));
    }

    private Path createTempFile() {
        try {
            Files.createDirectories(tmpDir);
            return Files.createTempFile(tmpDir, "kb-upload-", ".tmp");
        } catch (IOException e) {
            throw new BizException(ERR_STORAGE, "临时目录不可用：" + tmpDir);
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private void removeParts(String uploadId, int totalParts) {
        for (int part = 1; part <= totalParts; part++) {
            objectStorage.remove(partKey(uploadId, part));
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("临时文件清理失败: {}", path, e);
        }
    }

    private static String partKey(String uploadId, int partNumber) {
        return "_uploads/%s/part-%d".formatted(uploadId, partNumber);
    }

    private static String buildObjectKey(String tenantId, String kbId, String docId, int version, String fileName) {
        return "tenant/%s/kb/%s/doc/%s/v%d/%s".formatted(tenantId, kbId, docId, version, fileName);
    }

    private static String sanitizeFileName(String fileName) {
        return fileName == null ? "unnamed" : fileName.replace("/", "_").replace("\\", "_");
    }

    /** 由扩展名推导文档类型（与 AI 域解析分派保持一致）。 */
    private static String docTypeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "pdf" -> "pdf";
            case "doc", "docx" -> "docx";
            case "ppt", "pptx" -> "pptx";
            case "xls", "xlsx", "csv", "tsv" -> "sheet";
            case "html", "htm" -> "html";
            case "md", "markdown" -> "markdown";
            default -> "text";
        };
    }

    /** 暂存结果：临时文件路径 + 真实字节数 + 内容 SHA-256。 */
    private record Staged(Path path, long size, String sha256) {
    }
}
