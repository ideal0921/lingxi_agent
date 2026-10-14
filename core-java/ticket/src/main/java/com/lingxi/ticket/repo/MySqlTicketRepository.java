package com.lingxi.ticket.repo;

import com.lingxi.ticket.model.Ticket;
import com.lingxi.ticket.model.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
    public Optional<Ticket> findById(String ticketId) {
        List<Ticket> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE ticket_id = ?", ROW_MAPPER, ticketId);
        return result.stream().findFirst();
    }

    @Override
    public List<Ticket> listByAgent(String agentId) {
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE agent_id = ? ORDER BY updated_at DESC",
                ROW_MAPPER, agentId);
    }

    @Override
    public List<Ticket> listByStatus(TicketStatus status) {
        if (status == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM " + TABLE + " ORDER BY updated_at DESC", ROW_MAPPER);
        }
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE status = ? ORDER BY updated_at DESC",
                ROW_MAPPER, status.name());
    }

    @Override
    public int updateStatus(String ticketId, TicketStatus status, long updatedAtMs) {
        return jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ?, updated_at = ? WHERE ticket_id = ? AND status <> ?",
                status.name(), updatedAtMs, ticketId, status.name());
    }
}
