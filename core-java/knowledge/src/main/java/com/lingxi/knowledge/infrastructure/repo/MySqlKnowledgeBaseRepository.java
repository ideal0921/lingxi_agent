package com.lingxi.knowledge.infrastructure.repo;

import com.lingxi.knowledge.domain.KbStatus;
import com.lingxi.knowledge.domain.KnowledgeBase;
import com.lingxi.knowledge.domain.repo.KnowledgeBaseRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MySQL 知识库存储：JdbcTemplate 直连本地 MySQL（lingxi 库 lingxi_kb 表，启动时 schema.sql 建表）。
 * 采用 UPSERT 语义，便于同一 {@code save} 覆盖创建与状态变更。
 */
@Repository
public class MySqlKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private static final String TABLE = "lingxi_kb";

    private static final RowMapper<KnowledgeBase> ROW_MAPPER = (rs, rowNum) -> new KnowledgeBase(
            rs.getString("kb_id"),
            rs.getString("tenant_id"),
            rs.getString("name"),
            rs.getString("description"),
            KbStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlKnowledgeBaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public KnowledgeBase save(KnowledgeBase knowledgeBase) {
        jdbcTemplate.update("""
                INSERT INTO %s (kb_id, tenant_id, name, description, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description),
                    status = VALUES(status), updated_at = VALUES(updated_at)
                """.formatted(TABLE),
                knowledgeBase.getKbId(), knowledgeBase.getTenantId(), knowledgeBase.getName(),
                knowledgeBase.getDescription(), knowledgeBase.getStatus().name(),
                knowledgeBase.getCreatedAtMs(), knowledgeBase.getUpdatedAtMs());
        return knowledgeBase;
    }

    @Override
    public Optional<KnowledgeBase> findById(String kbId) {
        List<KnowledgeBase> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE kb_id = ?", ROW_MAPPER, kbId);
        return result.stream().findFirst();
    }

    @Override
    public List<KnowledgeBase> listByTenant(String tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE tenant_id = ? ORDER BY created_at DESC",
                ROW_MAPPER, tenantId);
    }

    @Override
    public void delete(String kbId) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE kb_id = ?", kbId);
    }
}
