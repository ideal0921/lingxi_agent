package com.lingxi.trace.infrastructure.repo;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lingxi.trace.application.ApiTraceQuery;
import com.lingxi.trace.domain.ApiTraceSpan;
import com.lingxi.trace.domain.repo.ApiTraceRepository;

/**
 * MySQL 链路 Span 存储（M5-5.9）：JdbcTemplate 直连 `lingxi_api_trace`（启动时 schema.sql 幂等建表）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>批量插入</b>：{@link #appendBatch} 用 {@code batchUpdate}，把异步批量写入器的批一次落库；</li>
 *   <li><b>租户隔离</b>：列表查询强制 {@code tenant_id} 条件；</li>
 *   <li><b>可切换</b>：经配置开关 {@code lingxi.trace.store} 二选一装配，默认本实现（mysql），
 *       范式对齐 {@code MySqlSessionRepository}。</li>
 * </ul>
 *
 * <p>只追加、不改：链路 span 是追加型事实，查询条件全部参数化，避免注入。</p>
 */
@Repository
@ConditionalOnProperty(name = "lingxi.trace.store", havingValue = "mysql", matchIfMissing = true)
public class MySqlApiTraceRepository implements ApiTraceRepository {

    private static final String TABLE = "lingxi_api_trace";

    private static final String INSERT_SQL = """
            INSERT INTO %s (span_id, trace_id, parent_span_id, service, component, entry, http_method,
                            status, status_code, tenant_id, user_id, role, client_ip, elapsed_ms,
                            error_msg, started_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(TABLE);

    private static final RowMapper<ApiTraceSpan> ROW_MAPPER = (rs, rowNum) -> new ApiTraceSpan(
            rs.getString("span_id"),
            rs.getString("trace_id"),
            rs.getString("parent_span_id"),
            rs.getString("service"),
            rs.getString("component"),
            rs.getString("entry"),
            rs.getString("http_method"),
            rs.getString("status"),
            rs.getInt("status_code"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("role"),
            rs.getString("client_ip"),
            rs.getLong("elapsed_ms"),
            rs.getString("error_msg"),
            rs.getLong("started_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlApiTraceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void appendBatch(List<ApiTraceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                ApiTraceSpan span = spans.get(index);
                ps.setString(1, span.spanId());
                ps.setString(2, span.traceId());
                ps.setString(3, span.parentSpanId());
                ps.setString(4, span.service());
                ps.setString(5, span.component());
                ps.setString(6, span.entry());
                ps.setString(7, span.httpMethod());
                ps.setString(8, span.status());
                ps.setInt(9, span.statusCode());
                ps.setString(10, span.tenantId());
                ps.setString(11, span.userId());
                ps.setString(12, span.role());
                ps.setString(13, span.clientIp());
                ps.setLong(14, span.elapsedMs());
                ps.setString(15, span.errorMsg());
                ps.setLong(16, span.startedAtMs());
            }

            @Override
            public int getBatchSize() {
                return spans.size();
            }
        });
    }

    @Override
    public List<ApiTraceSpan> list(ApiTraceQuery query, int offset, int limit) {
        SqlFilter filter = SqlFilter.of(query);
        String sql = "SELECT * FROM " + TABLE + filter.whereClause()
                + " ORDER BY started_at DESC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql, ROW_MAPPER, args.toArray());
    }

    @Override
    public long count(ApiTraceQuery query) {
        SqlFilter filter = SqlFilter.of(query);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + filter.whereClause(),
                Long.class, filter.args().toArray());
        return total == null ? 0L : total;
    }

    @Override
    public List<ApiTraceSpan> listByTraceId(String traceId) {
        // 按开始时间升序：管理端时间线按发生顺序渲染
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE trace_id = ? ORDER BY started_at ASC",
                ROW_MAPPER, traceId);
    }

    /** 查询条件拼装（参数化占位符 + 参数列表），保证 tenant 过滤恒生效。 */
    private record SqlFilter(String whereClause, List<Object> args) {

        private static SqlFilter of(ApiTraceQuery query) {
            List<String> conditions = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            conditions.add("tenant_id = ?");
            args.add(query.tenantId());
            if (query.traceId() != null) {
                conditions.add("trace_id = ?");
                args.add(query.traceId());
            }
            if (query.entry() != null) {
                conditions.add("entry = ?");
                args.add(query.entry());
            }
            if (query.status() != null) {
                conditions.add("status = ?");
                args.add(query.status());
            }
            if (query.fromMs() > 0) {
                conditions.add("started_at >= ?");
                args.add(query.fromMs());
            }
            if (query.toMs() > 0) {
                conditions.add("started_at <= ?");
                args.add(query.toMs());
            }
            return new SqlFilter(" WHERE " + String.join(" AND ", conditions), args);
        }
    }
}
