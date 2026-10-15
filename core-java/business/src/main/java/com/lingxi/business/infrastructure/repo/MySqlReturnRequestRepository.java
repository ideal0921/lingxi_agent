package com.lingxi.business.infrastructure.repo;

import com.lingxi.business.domain.BizReturnRequest;
import com.lingxi.business.domain.ReturnStatus;
import com.lingxi.business.domain.ReturnType;
import com.lingxi.business.domain.repo.ReturnRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MySQL 退换货受理单存储（biz_return_request 表）。
 *
 * <p>重复申请拦截依赖「已受理(status=ACCEPTED)查询」；受理单号为主键（UUID），
 * 并发重复提交由数据库唯一约束与业务校验共同兜底。</p>
 */
@Repository
public class MySqlReturnRequestRepository implements ReturnRequestRepository {

    private static final String TABLE = "biz_return_request";

    private static final RowMapper<BizReturnRequest> ROW_MAPPER = (rs, rowNum) -> new BizReturnRequest(
            rs.getString("return_id"),
            rs.getString("tenant_id"),
            rs.getString("order_no"),
            ReturnType.valueOf(rs.getString("return_type")),
            rs.getString("reason"),
            ReturnStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlReturnRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BizReturnRequest save(BizReturnRequest request) {
        jdbcTemplate.update("""
                INSERT INTO %s (return_id, tenant_id, order_no, return_type, reason, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status = VALUES(status), reason = VALUES(reason)
                """.formatted(TABLE),
                request.getReturnId(), request.getTenantId(), request.getOrderNo(),
                request.getReturnType().name(), request.getReason(),
                request.getStatus().name(), request.getCreatedAtMs());
        return request;
    }

    @Override
    public Optional<BizReturnRequest> findAcceptedByOrderNoAndTenant(String orderNo, String tenantId) {
        List<BizReturnRequest> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE order_no = ? AND tenant_id = ? AND status = ?",
                ROW_MAPPER, orderNo, tenantId, ReturnStatus.ACCEPTED.name());
        return result.stream().findFirst();
    }
}
