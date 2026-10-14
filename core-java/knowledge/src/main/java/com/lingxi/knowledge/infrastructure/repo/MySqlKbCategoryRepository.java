package com.lingxi.knowledge.infrastructure.repo;

import com.lingxi.knowledge.domain.KbCategory;
import com.lingxi.knowledge.domain.repo.KbCategoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MySQL 知识分类存储（lingxi_kb_category 表）。
 * 库内分类名唯一由唯一索引 uk_kb_name 兜底，写前校验见 {@link #existsByKbAndName}。
 */
@Repository
public class MySqlKbCategoryRepository implements KbCategoryRepository {

    private static final String TABLE = "lingxi_kb_category";

    private static final RowMapper<KbCategory> ROW_MAPPER = (rs, rowNum) -> new KbCategory(
            rs.getString("category_id"),
            rs.getString("kb_id"),
            rs.getString("tenant_id"),
            rs.getString("name"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlKbCategoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public KbCategory save(KbCategory category) {
        jdbcTemplate.update("""
                INSERT INTO %s (category_id, kb_id, tenant_id, name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), updated_at = VALUES(updated_at)
                """.formatted(TABLE),
                category.getCategoryId(), category.getKbId(), category.getTenantId(),
                category.getName(), category.getCreatedAtMs(), category.getUpdatedAtMs());
        return category;
    }

    @Override
    public Optional<KbCategory> findById(String categoryId) {
        List<KbCategory> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE category_id = ?", ROW_MAPPER, categoryId);
        return result.stream().findFirst();
    }

    @Override
    public List<KbCategory> listByKb(String kbId) {
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE kb_id = ? ORDER BY name ASC", ROW_MAPPER, kbId);
    }

    @Override
    public boolean existsByKbAndName(String kbId, String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + TABLE + " WHERE kb_id = ? AND name = ?",
                Integer.class, kbId, name);
        return count != null && count > 0;
    }

    @Override
    public void delete(String categoryId) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE category_id = ?", categoryId);
    }
}
