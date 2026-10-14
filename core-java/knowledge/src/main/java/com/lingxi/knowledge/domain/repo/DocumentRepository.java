package com.lingxi.knowledge.domain.repo;

import com.lingxi.knowledge.domain.Document;
import com.lingxi.knowledge.domain.DocumentStatus;

import java.util.List;
import java.util.Optional;

/**
 * 文档仓储端口。
 */
public interface DocumentRepository {

    Document save(Document document);

    Optional<Document> findById(String docId);

    /** 按内容哈希查重（同知识库内唯一，用于识别重复上传）。 */
    Optional<Document> findByContentHash(String kbId, String contentHash);

    /** 列出知识库内文档；status 为 {@code null} 时不按状态过滤。 */
    List<Document> listByKb(String kbId, String tenantId, DocumentStatus status);

    /** 物理删除文档元数据（下线时对象存储与向量清理另由应用层编排）。 */
    void delete(String docId);
}
