package com.lingxi.ticket.model;

/**
 * 工单状态：单向流转，关闭为终态。
 * 迁移规则：CREATED → {PROCESSING, CLOSED}；PROCESSING → {CLOSED}；CLOSED → ∅。
 */
public enum TicketStatus {
    CREATED,
    PROCESSING,
    CLOSED
}
