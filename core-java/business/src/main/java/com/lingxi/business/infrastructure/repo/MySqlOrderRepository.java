package com.lingxi.business.infrastructure.repo;

import com.lingxi.business.domain.BizOrder;
import com.lingxi.business.domain.OrderStatus;
import com.lingxi.business.domain.repo.OrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MySQL 订单存储（biz_order 表，启动时 schema.sql 幂等建表）。
 *
 * <p>查询固定携带 tenant_id 条件：租户隔离在 SQL 层强制生效，应用层无需再过滤；
 * 写入用 UPSERT，便于后续从交易系统同步订单时重复覆盖。</p>
 */
@Repository
public class MySqlOrderRepository implements OrderRepository {

    private static final String TABLE = "biz_order";

    private static final RowMapper<BizOrder> ROW_MAPPER = (rs, rowNum) -> new BizOrder(
            rs.getString("order_no"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("amount"),
            rs.getString("items_summary"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BizOrder save(BizOrder order) {
        jdbcTemplate.update("""
                INSERT INTO %s (order_no, tenant_id, user_id, status, amount, items_summary, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id), user_id = VALUES(user_id),
                    status = VALUES(status), amount = VALUES(amount),
                    items_summary = VALUES(items_summary)
                """.formatted(TABLE),
                order.getOrderNo(), order.getTenantId(), order.getUserId(),
                order.getStatus().name(), order.getAmount(), order.getItemsSummary(),
                order.getCreatedAtMs());
        return order;
    }

    @Override
    public Optional<BizOrder> findByOrderNoAndTenant(String orderNo, String tenantId) {
        List<BizOrder> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE order_no = ? AND tenant_id = ?",
                ROW_MAPPER, orderNo, tenantId);
        return result.stream().findFirst();
    }
}
