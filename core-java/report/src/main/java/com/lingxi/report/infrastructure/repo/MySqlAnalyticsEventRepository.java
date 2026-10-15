package com.lingxi.report.infrastructure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.lingxi.report.domain.AnalyticsEvent;
import com.lingxi.report.domain.repo.AnalyticsEventRepository;

/**
 * MySQL 分析明细存储（M5-5.1）：{@code INSERT IGNORE} + 主键 {@code event_id} 实现幂等。
 *
 * <p>Kafka 至少一次语义下同一事件可能重投，主键冲突即被忽略；{@code update} 返回受影响行数，
 * 为 0 说明重复投递，应用层据此跳过聚合更新。</p>
 */
@Repository
public class MySqlAnalyticsEventRepository implements AnalyticsEventRepository {

    private static final String TABLE = "lingxi_analytics_event";

    private final JdbcTemplate jdbcTemplate;

    public MySqlAnalyticsEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insertIgnore(AnalyticsEvent event) {
        int affected = jdbcTemplate.update(
                "INSERT IGNORE INTO " + TABLE
                        + " (event_id, event_type, tenant_id, session_id, occurred_at, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                event.eventId(), event.type().wire(), event.tenantId(), event.sessionId(),
                event.occurredAt(), event.payloadJson() == null ? "" : event.payloadJson());
        return affected > 0;
    }
}
