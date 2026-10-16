package com.lingxi.log.infrastructure.repo;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lingxi.log.application.OperationLogQuery;
import com.lingxi.log.domain.OperationLog;
import com.lingxi.log.domain.repo.OperationLogRepository;

/**
 * MySQL 操作日志存储（M5-5.6）：JdbcTemplate 直连 `lingxi_operation_log`（启动时 schema.sql 幂等建表）。
 * 只追加；查询条件与分页参数全部参数化，避免注入。
 */
@Repository
public class MySqlOperationLogRepository implements OperationLogRepository {

    private static final String TABLE = "lingxi_operation_log";

    private static final RowMapper<OperationLog> ROW_MAPPER = (rs, rowNum) -> new OperationLog(
            rs.getString("log_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("username"),
            rs.getString("role"),
            rs.getString("module"),
            rs.getString("action"),
            rs.getString("resource_type"),
            rs.getString("resource_id"),
            rs.getString("result"),
            rs.getString("detail"),
            rs.getString("client_ip"),
            rs.getString("user_agent"),
            rs.getLong("elapsed_ms"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlOperationLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(OperationLog entry) {
        jdbcTemplate.update("""
                INSERT INTO %s (log_id, tenant_id, user_id, username, role, module, action,
                                resource_type, resource_id, result, detail, client_ip, user_agent,
                                elapsed_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(TABLE),
                entry.logId(), entry.tenantId(), entry.userId(), entry.username(), entry.role(),
                entry.module(), entry.action(), entry.resourceType(), entry.resourceId(),
                entry.result(), entry.detail(), entry.clientIp(), entry.userAgent(),
                entry.elapsedMs(), entry.createdAtMs());
    }

    @Override
    public List<OperationLog> list(OperationLogQuery query, int offset, int limit) {
        SqlFilter filter = SqlFilter.of(query);
        String sql = "SELECT * FROM " + TABLE + filter.whereClause()
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql, ROW_MAPPER, args.toArray());
    }

    @Override
    public long count(OperationLogQuery query) {
        SqlFilter filter = SqlFilter.of(query);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + filter.whereClause(),
                Long.class, filter.args().toArray());
        return total == null ? 0L : total;
    }

    /** 查询条件拼装（参数化占位符 + 参数列表），保证 tenant 过滤恒生效。 */
    private record SqlFilter(String whereClause, List<Object> args) {

        private static SqlFilter of(OperationLogQuery query) {
            List<String> conditions = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            conditions.add("tenant_id = ?");
            args.add(query.tenantId());
            if (query.module() != null) {
                conditions.add("module = ?");
                args.add(query.module());
            }
            if (query.action() != null) {
                conditions.add("action = ?");
                args.add(query.action());
            }
            if (query.result() != null) {
                conditions.add("result = ?");
                args.add(query.result());
            }
            if (query.fromMs() > 0) {
                conditions.add("created_at >= ?");
                args.add(query.fromMs());
            }
            if (query.toMs() > 0) {
                conditions.add("created_at <= ?");
                args.add(query.toMs());
            }
            return new SqlFilter(" WHERE " + String.join(" AND ", conditions), args);
        }
    }
}
