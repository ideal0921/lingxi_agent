package com.lingxi.ticket.grpc;

import com.lingxi.ticket.model.Ticket;
import com.lingxi.ticket.model.TicketStatus;
import com.lingxi.ticket.repo.TicketRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lingxi.ticket.v1.TicketOuterClass;
import lingxi.ticket.v1.TicketServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工单域 gRPC 服务实现：建单、状态流转与列表查询，MySQL 持久化。
 * 非法流转与重复关闭被拒绝并返回原因。
 */
@Component
public class TicketServiceGrpcImpl extends TicketServiceGrpc.TicketServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(TicketServiceGrpcImpl.class);

    private final TicketRepository repository;

    public TicketServiceGrpcImpl(TicketRepository repository) {
        this.repository = repository;
    }

    @Override
    public void createTicket(TicketOuterClass.CreateTicketRequest request,
                             StreamObserver<TicketOuterClass.CreateTicketResponse> observer) {
        try {
            validateCreateRequest(request);
            Ticket ticket = new Ticket(request.getTenantId(), request.getSessionId(),
                    request.getUserId(), request.getAgentId(),
                    request.getTitle(), request.getDescription());
            repository.save(ticket);
            log.info("创建工单 ticketId={} sessionId={} agentId={}", ticket.getTicketId(),
                    ticket.getSessionId(), ticket.getAgentId());

            observer.onNext(TicketOuterClass.CreateTicketResponse.newBuilder()
                    .setTicket(toProtoTicket(ticket))
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void updateTicketStatus(TicketOuterClass.UpdateTicketStatusRequest request,
                                   StreamObserver<TicketOuterClass.UpdateTicketStatusResponse> observer) {
        Ticket ticket = requireTicket(request.getTicketId(), observer);
        if (ticket == null) {
            return;
        }
        TicketStatus target = fromProtoStatus(request.getTargetStatus());
        if (target == null) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("非法目标状态").asRuntimeException());
            return;
        }
        if (!ticket.transition(target)) {
            log.warn("非法工单流转被拒绝 ticketId={} {} -> {}", ticket.getTicketId(),
                    ticket.getStatus(), target);
            observer.onNext(TicketOuterClass.UpdateTicketStatusResponse.newBuilder()
                    .setOk(false)
                    .setReason("非法状态流转，当前状态为 " + ticket.getStatus())
                    .setTicket(toProtoTicket(ticket))
                    .build());
            observer.onCompleted();
            return;
        }
        // 条件更新：目标状态相同则不写入（并发防覆盖），返回行数为 0 视为幂等成功
        repository.updateStatus(ticket.getTicketId(), ticket.getStatus(), ticket.getUpdatedAtMs());
        observer.onNext(TicketOuterClass.UpdateTicketStatusResponse.newBuilder()
                .setOk(true)
                .setTicket(toProtoTicket(ticket))
                .build());
        observer.onCompleted();
    }

    @Override
    public void listTickets(TicketOuterClass.ListTicketsRequest request,
                            StreamObserver<TicketOuterClass.ListTicketsResponse> observer) {
        List<Ticket> tickets;
        if (!request.getAgentId().isBlank()) {
            tickets = repository.listByAgent(request.getAgentId());
        } else {
            tickets = repository.listByStatus(fromProtoStatus(request.getStatus()));
        }
        TicketOuterClass.ListTicketsResponse.Builder builder = TicketOuterClass.ListTicketsResponse.newBuilder();
        for (Ticket t : tickets) {
            builder.addTickets(toProtoTicket(t));
        }
        observer.onNext(builder.build());
        observer.onCompleted();
    }

    /** 校验建单请求参数。 */
    private void validateCreateRequest(TicketOuterClass.CreateTicketRequest request) {
        if (request.getTenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (request.getSessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (request.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (request.getAgentId().isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
    }

    /** 按工单 ID 查找，不存在时返回 NOT_FOUND。 */
    private Ticket requireTicket(String ticketId, StreamObserver<?> observer) {
        if (ticketId.isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("ticketId 不能为空").asRuntimeException());
            return null;
        }
        var ticket = repository.findById(ticketId);
        if (ticket.isEmpty()) {
            observer.onError(Status.NOT_FOUND.withDescription("工单不存在: " + ticketId).asRuntimeException());
            return null;
        }
        return ticket.get();
    }

    /** 领域工单 → proto 工单。 */
    private TicketOuterClass.Ticket toProtoTicket(Ticket t) {
        return TicketOuterClass.Ticket.newBuilder()
                .setTicketId(t.getTicketId())
                .setTenantId(t.getTenantId())
                .setSessionId(t.getSessionId())
                .setUserId(t.getUserId())
                .setAgentId(t.getAgentId())
                .setTitle(t.getTitle())
                .setDescription(t.getDescription())
                .setStatus(toProtoStatus(t.getStatus()))
                .setCreatedAtMs(t.getCreatedAtMs())
                .setUpdatedAtMs(t.getUpdatedAtMs())
                .build();
    }

    /** 本地状态 → proto 枚举。 */
    private TicketOuterClass.TicketStatus toProtoStatus(TicketStatus status) {
        return switch (status) {
            case CREATED -> TicketOuterClass.TicketStatus.CREATED;
            case PROCESSING -> TicketOuterClass.TicketStatus.PROCESSING;
            case CLOSED -> TicketOuterClass.TicketStatus.CLOSED;
        };
    }

    /** proto 枚举 → 本地状态，未知值返回 null。 */
    private TicketStatus fromProtoStatus(TicketOuterClass.TicketStatus status) {
        return switch (status) {
            case CREATED -> TicketStatus.CREATED;
            case PROCESSING -> TicketStatus.PROCESSING;
            case CLOSED -> TicketStatus.CLOSED;
            default -> null;
        };
    }
}
