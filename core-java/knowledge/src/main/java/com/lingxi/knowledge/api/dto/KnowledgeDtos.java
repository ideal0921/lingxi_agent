package com.lingxi.knowledge.api.dto;

import com.lingxi.knowledge.domain.Document;
import com.lingxi.knowledge.domain.KbCategory;
import com.lingxi.knowledge.domain.KnowledgeBase;
import com.lingxi.knowledge.domain.UploadSession;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库域 REST 入参/出参定义（集中声明，便于契约稳定与前端对齐）。
 *
 * <p>出参提供 {@code from(领域实体)} 工厂，避免控制器内散落拼装逻辑。</p>
 */
public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    public record CreateKbRequest(@NotBlank(message = "不能为空") String tenantId,
                                  @NotBlank(message = "不能为空") String name,
                                  String description) {
    }

    public record UpdateKbRequest(@NotBlank(message = "不能为空") String name, String description) {
    }

    public record ChangeStatusRequest(@NotBlank(message = "不能为空") String status) {
    }

    public record CreateCategoryRequest(@NotBlank(message = "不能为空") String name) {
    }

    public record InitUploadRequest(@NotBlank(message = "不能为空") String tenantId,
                                    @NotBlank(message = "不能为空") String kbId,
                                    String docId,
                                    String title,
                                    String categoryId,
                                    String tags,
                                    @NotBlank(message = "不能为空") String fileName,
                                    @NotNull(message = "不能为空")
                                    @Min(value = 1, message = "至少为 1")
                                    @Max(value = 10000, message = "至多为 10000") Integer totalParts) {
    }

    public record KbView(String kbId, String tenantId, String name, String description,
                         String status, long createdAt, long updatedAt) {

        public static KbView from(KnowledgeBase kb) {
            return new KbView(kb.getKbId(), kb.getTenantId(), kb.getName(), kb.getDescription(),
                    kb.getStatus().name(), kb.getCreatedAtMs(), kb.getUpdatedAtMs());
        }
    }

    public record CategoryView(String categoryId, String kbId, String tenantId, String name,
                               long createdAt, long updatedAt) {

        public static CategoryView from(KbCategory category) {
            return new CategoryView(category.getCategoryId(), category.getKbId(), category.getTenantId(),
                    category.getName(), category.getCreatedAtMs(), category.getUpdatedAtMs());
        }
    }

    public record DocumentView(String docId, String kbId, String tenantId, String categoryId,
                               String title, String fileName, String docType, int version,
                               long sizeBytes, String tags, String status, String statusMessage,
                               int childCount, int parentCount, long createdAt, long updatedAt) {

        public static DocumentView from(Document document) {
            return new DocumentView(document.getDocId(), document.getKbId(), document.getTenantId(),
                    document.getCategoryId(), document.getTitle(), document.getFileName(),
                    document.getDocType(), document.getVersion(), document.getSizeBytes(),
                    document.getTags(), document.getStatus().name(), document.getStatusMessage(),
                    document.getChildCount(), document.getParentCount(), document.getCreatedAtMs(),
                    document.getUpdatedAtMs());
        }
    }

    public record UploadSessionView(String uploadId, String fileName, int totalParts,
                                    int partSizeBytes, List<Integer> receivedParts, String status) {

        public static UploadSessionView from(UploadSession session) {
            return new UploadSessionView(session.getUploadId(), session.getFileName(),
                    session.getTotalParts(), session.getPartSizeBytes(),
                    new ArrayList<>(session.getReceivedParts()), session.getStatus().name());
        }
    }
}
