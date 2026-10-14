package com.lingxi.knowledge.infrastructure.repo;

import com.lingxi.knowledge.domain.Document;
import com.lingxi.knowledge.domain.DocumentStatus;
import com.lingxi.knowledge.domain.repo.DocumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MySQL 文档元数据存储（lingxi_kb_document 表）。
 *
 * <p>去重依赖唯一索引 {@code uk_kb_hash (kb_id, content_hash)}；状态回写与版本覆盖
 * 均走 UPSERT，避免先查后改的竞态。</p>
 */
@Repository
public class MySqlDocumentRepository implements DocumentRepository {

    private static final String TABLE = "lingxi_kb_document";

    private static final RowMapper<Document> ROW_MAPPER = (rs, rowNum) -> new Document(
            rs.getString("doc_id"),
            rs.getString("kb_id"),
            rs.getString("tenant_id"),
            rs.getString("category_id"),
            rs.getString("title"),
            rs.getString("file_name"),
            rs.getString("doc_type"),
            rs.getInt("version"),
            rs.getString("content_hash"),
            rs.getLong("size_bytes"),
            rs.getString("object_key"),
            rs.getString("tags"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("status_message"),
            rs.getInt("child_count"),
            rs.getInt("parent_count"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Document save(Document document) {
        jdbcTemplate.update("""
                INSERT INTO %s (doc_id, kb_id, tenant_id, category_id, title, file_name, doc_type,
                                version, content_hash, size_bytes, object_key, tags, status,
                                status_message, child_count, parent_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE category_id = VALUES(category_id), title = VALUES(title),
                    file_name = VALUES(file_name), doc_type = VALUES(doc_type),
                    version = VALUES(version), content_hash = VALUES(content_hash),
                    size_bytes = VALUES(size_bytes), object_key = VALUES(object_key),
                    tags = VALUES(tags), status = VALUES(status),
                    status_message = VALUES(status_message), child_count = VALUES(child_count),
                    parent_count = VALUES(parent_count), updated_at = VALUES(updated_at)
                """.formatted(TABLE),
                document.getDocId(), document.getKbId(), document.getTenantId(),
                document.getCategoryId(), document.getTitle(), document.getFileName(),
                document.getDocType(), document.getVersion(), document.getContentHash(),
                document.getSizeBytes(), document.getObjectKey(), document.getTags(),
                document.getStatus().name(), document.getStatusMessage(),
                document.getChildCount(), document.getParentCount(),
                document.getCreatedAtMs(), document.getUpdatedAtMs());
        return document;
    }

    @Override
    public Optional<Document> findById(String docId) {
        List<Document> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE doc_id = ?", ROW_MAPPER, docId);
        return result.stream().findFirst();
    }

    @Override
    public Optional<Document> findByContentHash(String kbId, String contentHash) {
        List<Document> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE kb_id = ? AND content_hash = ?",
                ROW_MAPPER, kbId, contentHash);
        return result.stream().findFirst();
    }

    @Override
    public List<Document> listByKb(String kbId, String tenantId, DocumentStatus status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(TABLE)
                .append(" WHERE kb_id = ? AND tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(kbId, tenantId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY updated_at DESC");
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    @Override
    public void delete(String docId) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE doc_id = ?", docId);
    }
}
