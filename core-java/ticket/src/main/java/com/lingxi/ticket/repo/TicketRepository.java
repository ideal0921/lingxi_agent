package com.lingxi.ticket.repo;

import java.util.List;
import java.util.Optional;

import com.lingxi.ticket.model.Ticket;
import com.lingxi.ticket.model.TicketStatus;

/**
 * 工单存储抽象：默认 MySQL 实现（JdbcTemplate），内存实现供单元测试。
 * 并发更新冲突由存储层行锁/条件更新兜底（见 MySqlTicketRepository）。
 *
 * <p>M5-5.5 租户隔离：查询方法一律要求租户入参，从存储层强制过滤，
 * 避免坐席跨租户查看/流转工单。</p>
 */
public interface TicketRepository {

    Ticket save(Ticket ticket);

    /** 租户内按工单 ID 查询：租户不匹配时返回空。 */
    Optional<Ticket> findById(String ticketId, String tenantId);

    /**
     * 工单是否归属**其他租户**（仅用于越权定性审计，不返回任何业务数据）。
     * 相比把租户校验完全交给 SQL 过滤，本方法让「越权」与「不存在」在审计上可区分。
     */
    boolean existsInOtherTenant(String ticketId, String tenantId);

    /** 租户内按处理坐席查询工单。 */
    List<Ticket> listByAgent(String agentId, String tenantId);

    /** 租户内按状态查询工单（status 为 null 表示全部）。 */
    List<Ticket> listByStatus(TicketStatus status, String tenantId);

    /** 更新状态与更新时间戳，返回受影响行数（0 表示并发冲突未更新）。 */
    int updateStatus(String ticketId, TicketStatus status, long updatedAtMs);
}
