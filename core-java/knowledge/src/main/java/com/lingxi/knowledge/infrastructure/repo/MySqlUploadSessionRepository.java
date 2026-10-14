package com.lingxi.knowledge.infrastructure.repo;

import com.lingxi.knowledge.domain.UploadSession;
import com.lingxi.knowledge.domain.repo.UploadSessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MySQL 分片上传会话存储（lingxi_kb_upload 表）。
 * 已收分片以逗号分隔的序号串存储，重传同一分片幂等（集合语义）。
 */
@Repository
public class MySqlUploadSessionRepository implements UploadSessionRepository {

    private static final String TABLE = "lingxi_kb_upload";

    private static final RowMapper<UploadSession> ROW_MAPPER = (rs, rowNum) -> new UploadSession(
            rs.getString("upload_id"),
            rs.getString("tenant_id"),
            rs.getString("kb_id"),
            rs.getString("doc_id"),
            rs.getString("title"),
            rs.getString("file_name"),
            rs.getString("category_id"),
            rs.getString("tags"),
            rs.getInt("total_parts"),
            rs.getInt("part_size"),
            parseParts(rs.getString("received_parts")),
            UploadSession.UploadStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlUploadSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UploadSession save(UploadSession session) {
        jdbcTemplate.update("""
                INSERT INTO %s (upload_id, tenant_id, kb_id, doc_id, title, file_name, category_id,
                                tags, total_parts, part_size, received_parts, status,
                                created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE received_parts = VALUES(received_parts),
                    status = VALUES(status), updated_at = VALUES(updated_at)
                """.formatted(TABLE),
                session.getUploadId(), session.getTenantId(), session.getKbId(), session.getDocId(),
                session.getTitle(), session.getFileName(), session.getCategoryId(), session.getTags(),
                session.getTotalParts(), session.getPartSizeBytes(), formatParts(session.getReceivedParts()),
                session.getStatus().name(), session.getCreatedAtMs(), session.getUpdatedAtMs());
        return session;
    }

    @Override
    public Optional<UploadSession> findById(String uploadId) {
        List<UploadSession> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE upload_id = ?", ROW_MAPPER, uploadId);
        return result.stream().findFirst();
    }

    @Override
    public void delete(String uploadId) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE upload_id = ?", uploadId);
    }

    private static String formatParts(Set<Integer> parts) {
        return parts.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<Integer> parseParts(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(Integer::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
