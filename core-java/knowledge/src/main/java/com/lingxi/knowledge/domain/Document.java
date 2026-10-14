package com.lingxi.knowledge.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文档聚合：知识库内一篇文档的元数据 + 当前版本 + 切片（入库）状态。
 *
 * <p><b>版本管理</b>：同一逻辑文档保持稳定 {@code docId}，重新上传以「新版本」方式
 * 覆盖文件与向量来源信息，状态回到 {@link DocumentStatus#PENDING}；Milvus 侧按
 * {@code docId} 全量替换切片，避免新旧切片混杂。</p>
 *
 * <p><b>去重</b>：内容 SHA-256（{@code contentHash}）在知识库内唯一，重复上传
 * 相同内容由应用层识别并拒绝。</p>
 */
public class Document {

    private static final int MAX_TITLE_LEN = 255;
    private static final int MAX_FILE_NAME_LEN = 255;
    private static final int MAX_OBJECT_KEY_LEN = 512;
    private static final int MAX_STATUS_MSG_LEN = 1000;

    private final String docId;
    private final String kbId;
    private final String tenantId;
    private final String categoryId;
    private final String title;
    private final String fileName;
    private final String docType;
    private int version;
    private String contentHash;
    private long sizeBytes;
    private String objectKey;
    private final String tags;
    private DocumentStatus status;
    private String statusMessage;
    private int childCount;
    private int parentCount;
    private final long createdAtMs;
    private long updatedAtMs;

    /** 新建文档（首版，状态待入库）。 */
    public Document(String kbId, String tenantId, String categoryId, String title, String fileName,
                    String docType, String contentHash, long sizeBytes, String objectKey, String tags) {
        this(UUID.randomUUID().toString(), kbId, tenantId, categoryId, title, fileName, docType,
                contentHash, sizeBytes, objectKey, tags);
    }

    /**
     * 新建文档并显式指定 {@code docId}。
     *
     * <p>对象路径需要携带 docId（{@code tenant/kb/doc/version}），故由应用层先生成
     * docId 再构造实体，避免「先建实体后改路径」的往返。</p>
     */
    public Document(String docId, String kbId, String tenantId, String categoryId, String title,
                    String fileName, String docType, String contentHash, long sizeBytes,
                    String objectKey, String tags) {
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("知识库 ID 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        this.docId = docId;
        this.kbId = kbId;
        this.tenantId = tenantId;
        this.categoryId = categoryId == null ? "" : categoryId;
        this.title = validateText(title, "文档标题", MAX_TITLE_LEN, true);
        this.fileName = validateText(fileName, "文件名", MAX_FILE_NAME_LEN, true);
        this.docType = validateText(docType, "文档类型", 16, true);
        this.contentHash = validateHash(contentHash);
        this.sizeBytes = sizeBytes;
        this.objectKey = validateText(objectKey, "对象路径", MAX_OBJECT_KEY_LEN, true);
        this.tags = normalizeTags(tags);
        this.version = 1;
        this.status = DocumentStatus.PENDING;
        this.statusMessage = "";
        this.createdAtMs = System.currentTimeMillis();
        this.updatedAtMs = this.createdAtMs;
    }

    /** 仅供仓储从持久化数据恢复。 */
    public Document(String docId, String kbId, String tenantId, String categoryId, String title,
                    String fileName, String docType, int version, String contentHash, long sizeBytes,
                    String objectKey, String tags, DocumentStatus status, String statusMessage,
                    int childCount, int parentCount, long createdAtMs, long updatedAtMs) {
        this.docId = docId;
        this.kbId = kbId;
        this.tenantId = tenantId;
        this.categoryId = categoryId;
        this.title = title;
        this.fileName = fileName;
        this.docType = docType;
        this.version = version;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.objectKey = objectKey;
        this.tags = tags;
        this.status = status;
        this.statusMessage = statusMessage;
        this.childCount = childCount;
        this.parentCount = parentCount;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    /** 上传新版本：替换文件与向量来源信息，状态回到待入库（触发重新切片）。 */
    public boolean applyNewVersion(int version, String contentHash, long sizeBytes, String objectKey) {
        this.version = version;
        this.contentHash = validateHash(contentHash);
        this.sizeBytes = sizeBytes;
        this.objectKey = validateText(objectKey, "对象路径", MAX_OBJECT_KEY_LEN, true);
        this.childCount = 0;
        this.parentCount = 0;
        this.statusMessage = "";
        return transition(DocumentStatus.PENDING);
    }

    /** AI 域回写：入库成功。 */
    public boolean markIndexed(int childCount, int parentCount) {
        this.childCount = childCount;
        this.parentCount = parentCount;
        this.statusMessage = "";
        return transition(DocumentStatus.INDEXED);
    }

    /** AI 域回写：入库失败（携带原因，支持后续重试）。 */
    public boolean markFailed(String message) {
        this.statusMessage = validateText(message, "失败原因", MAX_STATUS_MSG_LEN, false);
        return transition(DocumentStatus.FAILED);
    }

    /** 下线文档（逻辑删除，终态）。 */
    public boolean markDeleted() {
        return transition(DocumentStatus.DELETED);
    }

    /**
     * 释放库内内容哈希唯一槽位（下线时调用）。
     *
     * <p>库表唯一索引为 {@code (kb_id, content_hash)}：逻辑删除保留元数据行，
     * 若不释放槽位，同一内容再次上传会撞唯一键。此处把哈希改写为与原内容无关的
     * 占位值（由 docId 派生），既保留审计行，又允许相同内容重新上传。</p>
     */
    public void releaseContentHashSlot() {
        this.contentHash = sha256Hex("deleted:" + docId + ":" + contentHash);
        this.updatedAtMs = System.currentTimeMillis();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private boolean transition(DocumentStatus target) {
        if (!status.canTransitionTo(target)) {
            return false;
        }
        this.status = target;
        this.updatedAtMs = System.currentTimeMillis();
        return true;
    }

    private static String validateText(String value, String field, int maxLen, boolean required) {
        String text = value == null ? "" : value.trim();
        if (required && text.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        if (text.length() > maxLen) {
            throw new IllegalArgumentException(field + "超过上限 " + maxLen);
        }
        return text;
    }

    private static String validateHash(String hash) {
        String value = hash == null ? "" : hash.trim().toLowerCase();
        if (value.length() != 64) {
            throw new IllegalArgumentException("内容哈希必须为 64 位十六进制（SHA-256）");
        }
        return value;
    }

    /** 标签规范化：去空白、去重、逗号重排（空标签存空串）。 */
    private static String normalizeTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return "";
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
    }

    public String getDocId() {
        return docId;
    }

    public String getKbId() {
        return kbId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public String getDocType() {
        return docType;
    }

    public int getVersion() {
        return version;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getTags() {
        return tags;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public int getChildCount() {
        return childCount;
    }

    public int getParentCount() {
        return parentCount;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }
}
