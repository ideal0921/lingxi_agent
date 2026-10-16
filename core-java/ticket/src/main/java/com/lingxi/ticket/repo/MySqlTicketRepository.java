package com.lingxi.ticket.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lingxi.ticket.model.Ticket;
import com.lingxi.ticket.model.TicketStatus;

/**
 * MySQL 工单存储：JdbcTemplate 直连本地 MySQL（lingxi 库 lingxi_ticket 表，启动时 schema.sql 建表）。
 * 状态更新采用条件更新（WHERE status &lt;&gt; ?）防止并发重复流转覆盖。
 */
@Repository
public class MySqlTicketRepository implements TicketRepository {

    private static final String TABLE = "lingxi_ticket";

    private static final RowMapper<Ticket> ROW_MAPPER = (rs, rowNum) -> new Ticket(
            rs.getString("ticket_id"),
            rs.getString("tenant_id"),
            rs.getString("session_id"),
            rs.getString("user_id"),
            rs.getString("agent_id"),
            rs.getString("title"),
            rs.getString("description"),
            TicketStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlTicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Ticket save(Ticket ticket) {
        jdbcTemplate.update("""
                INSERT INTO %s (ticket_id, tenant_id, session_id, user_id, agent_id,
                                title, description, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(TABLE),
                ticket.getTicketId(), ticket.getTenantId(), ticket.getSessionId(),
                ticket.getUserId(), ticket.getAgentId(), ticket.getTitle(),
                ticket.getDescription(), ticket.getStatus().name(),
                ticket.getCreatedAtMs(), ticket.getUpdatedAtMs());
        return ticket;
    }

    @Override
    public Optional<Ticket> findById(String ticketId, String tenantId) {
        // M5-5.5：租户条件写入 SQL，从数据源侧强制隔离
        List<Ticket> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE ticket_id = ? AND tenant_id = ?",
                ROW_MAPPER, ticketId, tenantId);
        return result.stream().findFirst();
    }

    @Override
    public boolean existsInOtherTenant(String ticketId, String tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE ticket_id = ? AND tenant_id <> ?",
                Integer.class, ticketId, tenantId);
        return count != null && count > 0;
    }

    @Override
    public List<Ticket> listByAgent(String agentId, String tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE agent_id = ? AND tenant_id = ? ORDER BY updated_at DESC",
                ROW_MAPPER, agentId, tenantId);
    }

    @Override
    public List<Ticket> listByStatus(TicketStatus status, String tenantId) {
        if (status == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM " + TABLE + " WHERE tenant_id = ? ORDER BY updated_at DESC",
                    ROW_MAPPER, tenantId);
        }
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE status = ? AND tenant_id = ? ORDER BY updated_at DESC",
                ROW_MAPPER, status.name(), tenantId);
    }

    @Override
    public int updateStatus(String ticketId, TicketStatus status, long updatedAtMs) {
        return jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ?, updated_at = ? WHERE ticket_id = ? AND status <> ?",
                status.name(), updatedAtMs, ticketId, status.name());
    }
}
