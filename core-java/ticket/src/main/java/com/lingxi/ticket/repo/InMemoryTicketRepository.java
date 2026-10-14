package com.lingxi.ticket.repo;

import com.lingxi.ticket.model.Ticket;
import com.lingxi.ticket.model.TicketStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存工单存储：仅供单元测试使用（@Profile("ticket-mem")）。
 * 生产默认激活 MySqlTicketRepository。
 */
@Repository
@Profile("ticket-mem")
public class InMemoryTicketRepository implements TicketRepository {

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    @Override
    public Ticket save(Ticket ticket) {
        tickets.put(ticket.getTicketId(), ticket);
        return ticket;
    }

    @Override
    public Optional<Ticket> findById(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    @Override
    public List<Ticket> listByAgent(String agentId) {
        return tickets.values().stream()
                .filter(t -> agentId.equals(t.getAgentId()))
                .toList();
    }

    @Override
    public List<Ticket> listByStatus(TicketStatus status) {
        if (status == null) {
            return List.copyOf(tickets.values());
        }
        return tickets.values().stream()
                .filter(t -> t.getStatus() == status)
                .toList();
    }

    @Override
    public int updateStatus(String ticketId, TicketStatus status, long updatedAtMs) {
        Ticket ticket = tickets.get(ticketId);
        if (ticket == null) {
            return 0;
        }
        synchronized (ticket) {
            if (ticket.getStatus() == status) {
                return 0;
            }
            ticket.updateStatusInternal(status, updatedAtMs);
        }
        return 1;
    }
}
