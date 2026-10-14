package com.lingxi.ticket.repo;

import com.lingxi.ticket.model.Ticket;
import com.lingxi.ticket.model.TicketStatus;

import java.util.List;
import java.util.Optional;

/**
 * 工单存储抽象：默认 MySQL 实现（JdbcTemplate），内存实现供单元测试。
 * 并发更新冲突由存储层行锁/条件更新兜底（见 MySqlTicketRepository）。
 */
public interface TicketRepository {

    Ticket save(Ticket ticket);

    Optional<Ticket> findById(String ticketId);

    /** 按处理坐席查询工单。 */
    List<Ticket> listByAgent(String agentId);

    /** 按状态查询工单（status 为 null 表示全部）。 */
    List<Ticket> listByStatus(TicketStatus status);

    /** 更新状态与更新时间戳，返回受影响行数（0 表示并发冲突未更新）。 */
    int updateStatus(String ticketId, TicketStatus status, long updatedAtMs);
}
