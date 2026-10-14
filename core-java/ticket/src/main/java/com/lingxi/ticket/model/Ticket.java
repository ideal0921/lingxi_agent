package com.lingxi.ticket.model;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * 工单聚合实体：记录来源会话、用户与处理坐席，状态单向流转。
 *
 * <p>并发更新冲突由存储层保证（MySQL 行锁/条件更新），非法流转由
 * {@link #transition(TicketStatus)} 拒绝，保持状态机单一权威。</p>
 */
public class Ticket {

    private static final int MAX_TITLE_LEN = 255;
    private static final int MAX_DESC_LEN = 4000;

    private final String ticketId;
    private final String tenantId;
    private final String sessionId;
    private final String userId;
    private final String agentId;
    private final String title;
    private final String description;
    private final long createdAtMs;
    private TicketStatus status;
    private long updatedAtMs;

    public Ticket(String tenantId, String sessionId, String userId, String agentId,
                  String title, String description) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("工单标题不能为空");
        }
        if (title.length() > MAX_TITLE_LEN) {
            throw new IllegalArgumentException("工单标题超过上限 " + MAX_TITLE_LEN);
        }
        this.ticketId = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.agentId = agentId;
        this.title = title;
        this.description = description == null ? "" : description;
        if (this.description.length() > MAX_DESC_LEN) {
            throw new IllegalArgumentException("工单描述超过上限 " + MAX_DESC_LEN);
        }
        this.createdAtMs = System.currentTimeMillis();
        this.status = TicketStatus.CREATED;
        this.updatedAtMs = this.createdAtMs;
    }

    /** 仅供仓储从持久化数据恢复实体（跳过默认初始化，状态/时间由存储回填）。 */
    public Ticket(String ticketId, String tenantId, String sessionId, String userId, String agentId,
           String title, String description, TicketStatus status, long createdAtMs, long updatedAtMs) {
        this.ticketId = ticketId;
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.agentId = agentId;
        this.title = title;
        this.description = description;
        this.createdAtMs = createdAtMs;
        this.status = status;
        this.updatedAtMs = updatedAtMs;
    }

    /**
     * 状态流转：非法流转（如重复关闭、PROCESSING 回退）返回 false 并保持原状态。
     *
     * @param target 目标状态
     * @return 是否流转成功
     */
    public boolean transition(TicketStatus target) {
        if (!allowedTransitions(this.status).contains(target)) {
            return false;
        }
        this.status = target;
        this.updatedAtMs = System.currentTimeMillis();
        return true;
    }

    private static Set<TicketStatus> allowedTransitions(TicketStatus from) {
        return switch (from) {
            case CREATED -> EnumSet.of(TicketStatus.PROCESSING, TicketStatus.CLOSED);
            case PROCESSING -> EnumSet.of(TicketStatus.CLOSED);
            case CLOSED -> EnumSet.noneOf(TicketStatus.class);
        };
    }

    /** 仅供仓储持久化回填状态使用（状态合法性已由服务层校验）。 */
    public void updateStatusInternal(TicketStatus status, long updatedAtMs) {
        this.status = status;
        this.updatedAtMs = updatedAtMs;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }
}
