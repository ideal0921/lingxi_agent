package com.lingxi.knowledge.domain;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 分片上传会话：支撑超大文档的「初始化 → 逐片上传 → 合并」流程。
 *
 * <p>分片状态仅记录「已收到哪几片」，客户端可乱序/断点续传重传同一分片；
 * 合并前校验分片齐全，避免半成品文档进入入库管道。</p>
 */
public class UploadSession {

    /** 上传会话状态。 */
    public enum UploadStatus {
        OPEN,
        COMPLETED,
        ABORTED
    }

    private final String uploadId;
    private final String tenantId;
    private final String kbId;
    private final String docId;
    private final String title;
    private final String fileName;
    private final String categoryId;
    private final String tags;
    private final int totalParts;
    private final int partSizeBytes;
    private final Set<Integer> receivedParts;
    private UploadStatus status;
    private final long createdAtMs;
    private long updatedAtMs;

    /** 新建上传会话。 */
    public UploadSession(String tenantId, String kbId, String docId, String title, String fileName,
                         String categoryId, String tags, int totalParts, int partSizeBytes) {
        if (tenantId == null || tenantId.isBlank() || kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("租户与知识库 ID 不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (totalParts < 1 || totalParts > 10000) {
            throw new IllegalArgumentException("分片数量必须在 1~10000 之间");
        }
        if (partSizeBytes < 1) {
            throw new IllegalArgumentException("分片大小必须为正数");
        }
        this.uploadId = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.kbId = kbId;
        this.docId = docId == null ? "" : docId;
        this.title = title == null ? "" : title;
        this.fileName = fileName;
        this.categoryId = categoryId == null ? "" : categoryId;
        this.tags = tags == null ? "" : tags;
        this.totalParts = totalParts;
        this.partSizeBytes = partSizeBytes;
        this.receivedParts = new LinkedHashSet<>();
        this.status = UploadStatus.OPEN;
        this.createdAtMs = System.currentTimeMillis();
        this.updatedAtMs = this.createdAtMs;
    }

    /** 仅供仓储从持久化数据恢复（已收分片由存储回填）。 */
    public UploadSession(String uploadId, String tenantId, String kbId, String docId, String title,
                         String fileName, String categoryId, String tags, int totalParts,
                         int partSizeBytes, Set<Integer> receivedParts, UploadStatus status,
                         long createdAtMs, long updatedAtMs) {
        this.uploadId = uploadId;
        this.tenantId = tenantId;
        this.kbId = kbId;
        this.docId = docId;
        this.title = title;
        this.fileName = fileName;
        this.categoryId = categoryId;
        this.tags = tags;
        this.totalParts = totalParts;
        this.partSizeBytes = partSizeBytes;
        this.receivedParts = new LinkedHashSet<>(receivedParts);
        this.status = status;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    /** 会话是否可继续接收分片。 */
    public boolean isOpen() {
        return status == UploadStatus.OPEN;
    }

    /** 记录收到某一片（幂等：断点续传重传同一片不报错）。 */
    public void markPartReceived(int partNumber) {
        if (status != UploadStatus.OPEN) {
            throw new IllegalStateException("上传会话已结束，状态=" + status);
        }
        if (partNumber < 1 || partNumber > totalParts) {
            throw new IllegalArgumentException("分片序号越界：" + partNumber + "/" + totalParts);
        }
        receivedParts.add(partNumber);
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** 分片是否已全部到齐（合并前置校验）。 */
    public boolean allPartsReceived() {
        return receivedParts.size() == totalParts;
    }

    /** 合并完成，会话关闭。 */
    public void complete() {
        if (status == UploadStatus.ABORTED) {
            throw new IllegalStateException("上传会话已中止，无法完成");
        }
        if (!allPartsReceived()) {
            throw new IllegalStateException(
                    "分片不完整，已收 " + receivedParts.size() + "/" + totalParts);
        }
        this.status = UploadStatus.COMPLETED;
        this.updatedAtMs = System.currentTimeMillis();
    }

    /** 中止会话（客户端主动放弃）。 */
    public void abort() {
        this.status = UploadStatus.ABORTED;
        this.updatedAtMs = System.currentTimeMillis();
    }

    public String getUploadId() {
        return uploadId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getKbId() {
        return kbId;
    }

    public String getDocId() {
        return docId;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getTags() {
        return tags;
    }

    public int getTotalParts() {
        return totalParts;
    }

    public int getPartSizeBytes() {
        return partSizeBytes;
    }

    public Set<Integer> getReceivedParts() {
        return new LinkedHashSet<>(receivedParts);
    }

    public UploadStatus getStatus() {
        return status;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }
}
