package com.lingxi.business.infrastructure.repo;

import com.lingxi.business.domain.BizLogistics;
import com.lingxi.business.domain.LogisticsStatus;
import com.lingxi.business.domain.repo.LogisticsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MySQL 物流存储（biz_logistics 表，订单号唯一，仅保留最新轨迹）。
 *
 * <p>查询固定携带 tenant_id 条件：租户隔离在 SQL 层强制生效。</p>
 */
@Repository
public class MySqlLogisticsRepository implements LogisticsRepository {

    private static final String TABLE = "biz_logistics";

    private static final RowMapper<BizLogistics> ROW_MAPPER = (rs, rowNum) -> new BizLogistics(
            rs.getString("order_no"),
            rs.getString("tenant_id"),
            rs.getString("carrier"),
            rs.getString("tracking_no"),
            LogisticsStatus.valueOf(rs.getString("status")),
            rs.getString("latest_event"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlLogisticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BizLogistics save(BizLogistics logistics) {
        jdbcTemplate.update("""
                INSERT INTO %s (order_no, tenant_id, carrier, tracking_no, status, latest_event, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id), carrier = VALUES(carrier),
                    tracking_no = VALUES(tracking_no), status = VALUES(status),
                    latest_event = VALUES(latest_event), updated_at = VALUES(updated_at)
                """.formatted(TABLE),
                logistics.getOrderNo(), logistics.getTenantId(), logistics.getCarrier(),
                logistics.getTrackingNo(), logistics.getStatus().name(),
                logistics.getLatestEvent(), logistics.getUpdatedAtMs());
        return logistics;
    }

    @Override
    public Optional<BizLogistics> findByOrderNoAndTenant(String orderNo, String tenantId) {
        List<BizLogistics> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE order_no = ? AND tenant_id = ?",
                ROW_MAPPER, orderNo, tenantId);
        return result.stream().findFirst();
    }
}
