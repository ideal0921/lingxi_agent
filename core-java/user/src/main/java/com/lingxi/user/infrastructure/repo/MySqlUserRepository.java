package com.lingxi.user.infrastructure.repo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lingxi.user.domain.LingxiUser;
import com.lingxi.user.domain.UserRole;
import com.lingxi.user.domain.UserStatus;
import com.lingxi.user.domain.repo.UserRepository;

/**
 * MySQL 账号存储（M5-5.6）：JdbcTemplate 直连 `lingxi_user`（启动时 schema.sql 幂等建表）。
 * 所有 SQL 均带 `tenant_id` 条件；用户名租户内唯一由唯一索引兜底。
 */
@Repository
public class MySqlUserRepository implements UserRepository {

    private static final String TABLE = "lingxi_user";

    private static final RowMapper<LingxiUser> ROW_MAPPER = (rs, rowNum) -> new LingxiUser(
            rs.getString("user_id"),
            rs.getString("tenant_id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            UserRole.fromCode(rs.getString("role")),
            UserStatus.fromName(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"),
            rs.getLong("last_login_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<LingxiUser> findByUsername(String tenantId, String username) {
        List<LingxiUser> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE tenant_id = ? AND username = ?", ROW_MAPPER,
                tenantId, username);
        return result.stream().findFirst();
    }

    @Override
    public Optional<LingxiUser> findById(String userId, String tenantId) {
        List<LingxiUser> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE user_id = ? AND tenant_id = ?", ROW_MAPPER,
                userId, tenantId);
        return result.stream().findFirst();
    }

    @Override
    public boolean existsUsername(String tenantId, String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE tenant_id = ? AND username = ?",
                Long.class, tenantId, username);
        return count != null && count > 0;
    }

    @Override
    public LingxiUser save(LingxiUser user) {
        jdbcTemplate.update("""
                INSERT INTO %s (user_id, tenant_id, username, password_hash, display_name, role,
                                status, created_at, updated_at, last_login_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(TABLE),
                user.getUserId(), user.getTenantId(), user.getUsername(), user.getPasswordHash(),
                user.getDisplayName(), user.getRole().code(), user.getStatus().name(),
                user.getCreatedAtMs(), user.getUpdatedAtMs(), user.getLastLoginAtMs());
        return user;
    }

    @Override
    public int updatePasswordHash(String tenantId, String userId, String passwordHash, long atMs) {
        return jdbcTemplate.update(
                "UPDATE " + TABLE + " SET password_hash = ?, updated_at = ? WHERE user_id = ? AND tenant_id = ?",
                passwordHash, atMs, userId, tenantId);
    }

    @Override
    public int updateStatus(String tenantId, String userId, UserStatus status, long atMs) {
        return jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ?, updated_at = ? WHERE user_id = ? AND tenant_id = ?",
                status.name(), atMs, userId, tenantId);
    }

    @Override
    public int updateLastLogin(String tenantId, String userId, long atMs) {
        // 最近登录只推进时间戳，不改 updated_at（避免「登录」被误读为配置变更）
        return jdbcTemplate.update(
                "UPDATE " + TABLE + " SET last_login_at = ? WHERE user_id = ? AND tenant_id = ?",
                atMs, userId, tenantId);
    }

    @Override
    public long count(String tenantId, String keyword) {
        SqlFilter filter = SqlFilter.of(tenantId, keyword);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + filter.whereClause(), Long.class, filter.args().toArray());
        return total == null ? 0L : total;
    }

    @Override
    public List<LingxiUser> list(String tenantId, String keyword, int offset, int limit) {
        SqlFilter filter = SqlFilter.of(tenantId, keyword);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + filter.whereClause() + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, args.toArray());
    }

    /** 查询条件拼装（参数化），保证租户条件恒生效。 */
    private record SqlFilter(String whereClause, List<Object> args) {

        private static SqlFilter of(String tenantId, String keyword) {
            List<String> conditions = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            conditions.add("tenant_id = ?");
            args.add(tenantId);
            if (keyword != null && !keyword.isBlank()) {
                conditions.add("(username LIKE ? OR display_name LIKE ?)");
                String like = "%" + keyword.trim() + "%";
                args.add(like);
                args.add(like);
            }
            return new SqlFilter(" WHERE " + String.join(" AND ", conditions), args);
        }
    }
}
