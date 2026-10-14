package com.lingxi.ticket.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单状态机测试：创建默认态、单向流转、非法流转拒绝（M2-B 验收边界「非法流转拒绝」）。
 */
class TicketTest {

    private Ticket newTicket() {
        return new Ticket("tenant-1", "session-1", "user-1", "agent-1", "退款咨询", "订单超时未收到");
    }

    @Test
    void createDefaultsToCreated() {
        assertEquals(TicketStatus.CREATED, newTicket().getStatus());
    }

    @Test
    void forwardTransitionSucceeds() {
        Ticket ticket = newTicket();
        assertTrue(ticket.transition(TicketStatus.PROCESSING));
        assertEquals(TicketStatus.PROCESSING, ticket.getStatus());
        assertTrue(ticket.transition(TicketStatus.CLOSED));
        assertEquals(TicketStatus.CLOSED, ticket.getStatus());
    }

    @Test
    void createdCanCloseDirectly() {
        Ticket ticket = newTicket();
        assertTrue(ticket.transition(TicketStatus.CLOSED));
        assertEquals(TicketStatus.CLOSED, ticket.getStatus());
    }

    @Test
    void closedRejectsAnyTransition() {
        Ticket ticket = newTicket();
        ticket.transition(TicketStatus.CLOSED);
        assertFalse(ticket.transition(TicketStatus.PROCESSING), "关闭后不可重新流转");
        assertFalse(ticket.transition(TicketStatus.CREATED), "关闭后不可回退");
    }

    @Test
    void processingRejectsRevert() {
        Ticket ticket = newTicket();
        ticket.transition(TicketStatus.PROCESSING);
        assertFalse(ticket.transition(TicketStatus.CREATED), "PROCESSING 不可回退 CREATED");
    }

    @Test
    void blankTitleRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Ticket("t1", "s1", "u1", "a1", " ", "desc"),
                "工单标题必填");
    }
}
