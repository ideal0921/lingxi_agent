package com.lingxi.ticket.grpc;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lingxi.common.tenant.MissingTenantContextException;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIsolationAuditor;
import com.lingxi.common.tenant.TenantIsolationGuard;
import com.lingxi.ticket.model.Ticket;
import com.lingxi.ticket.model.TicketStatus;
import com.lingxi.ticket.repo.TicketRepository;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lingxi.ticket.v1.TicketOuterClass;
import lingxi.ticket.v1.TicketServiceGrpc;

/**
 * 工单域 gRPC 服务实现：建单、状态流转与列表查询，MySQL 持久化。
 * 非法流转与重复关闭被拒绝并返回原因。
 *
 * <p><b>租户隔离（M5-5.5）</b>：租户取自信任上下文（Go 网关经 metadata 下传）；
 * 建单拒绝「请求体租户 ≠ 身份租户」，查询/流转一律按租户过滤，
 * 越权对外按「不存在」返回并落隔离审计。</p>
 */
@Component
public class TicketServiceGrpcImpl extends TicketServiceGrpc.TicketServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(TicketServiceGrpcImpl.class);

    /** 审计资源与动作名（数据隔离审计维度）。 */
    private static final String RESOURCE_TICKET = "ticket";
    private static final String ACTION_CREATE = "create_ticket";
    private static final String ACTION_UPDATE_STATUS = "update_ticket_status";

    private final TicketRepository repository;
    private final TenantIsolationAuditor isolationAuditor;

    public TicketServiceGrpcImpl(TicketRepository repository, TenantIsolationAuditor isolationAuditor) {
        this.repository = repository;
        this.isolationAuditor = isolationAuditor;
    }

    @Override
    public void createTicket(TicketOuterClass.CreateTicketRequest request,
                             StreamObserver<TicketOuterClass.CreateTicketResponse> observer) {
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return;
        }
        // 请求体租户仅作兼容字段：与可信租户不一致即视为伪造，拒绝并审计
        if (!request.getTenantId().isBlank() && !tenantId.equals(request.getTenantId())) {
            reportViolation(RESOURCE_TICKET, "(new)", request.getTenantId(), ACTION_CREATE,
                    "请求体租户与身份租户不一致（疑似伪造）");
            observer.onError(Status.PERMISSION_DENIED
                    .withDescription("请求租户与身份租户不一致").asRuntimeException());
            return;
        }
        try {
            validateCreateRequest(request);
            Ticket ticket = new Ticket(tenantId, request.getSessionId(),
                    request.getUserId(), request.getAgentId(),
                    request.getTitle(), request.getDescription());
            repository.save(ticket);
            log.info("创建工单 ticketId={} tenantId={} sessionId={} agentId={}", ticket.getTicketId(),
                    tenantId, ticket.getSessionId(), ticket.getAgentId());

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
        Ticket ticket = requireOwnedTicket(request.getTicketId(), observer);
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
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return;
        }
        // M5-5.5：列表按租户过滤，坐席只能看到本租户工单
        List<Ticket> tickets;
        if (!request.getAgentId().isBlank()) {
            tickets = repository.listByAgent(request.getAgentId(), tenantId);
        } else {
            tickets = repository.listByStatus(fromProtoStatus(request.getStatus()), tenantId);
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

    /**
     * 租户内按工单 ID 查找；越权与不存在统一回 {@code NOT_FOUND}（避免枚举探测），
     * 真实越权事实写入隔离审计。
     *
     * @return 通过校验的工单；校验失败返回 null（观察者已收到错误）
     */
    private Ticket requireOwnedTicket(String ticketId, StreamObserver<?> observer) {
        if (ticketId == null || ticketId.isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("ticketId 不能为空").asRuntimeException());
            return null;
        }
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return null;
        }
        Optional<Ticket> found = repository.findById(ticketId, tenantId);
        if (found.isEmpty()) {
            // 越权定性审计：工单真实存在但归属其他租户时落审计（仅按「不存在」对外返回，避免枚举探测）
            if (repository.existsInOtherTenant(ticketId, tenantId)) {
                reportViolation(RESOURCE_TICKET, ticketId, "(other-tenant)", ACTION_UPDATE_STATUS, "工单归属其他租户");
            }
            observer.onError(Status.NOT_FOUND.withDescription("工单不存在: " + ticketId).asRuntimeException());
            return null;
        }
        return found.get();
    }

    /** 读取可信租户；缺失时返回 null 并已回 {@code UNAUTHENTICATED}。 */
    private String trustedTenantId(StreamObserver<?> observer) {
        try {
            return TenantContext.requireTenantId();
        } catch (MissingTenantContextException e) {
            log.warn("工单域调用缺少租户上下文已拒绝：{}", e.getMessage());
            observer.onError(Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
            return null;
        }
    }

    /** 上报一次跨租户越权（审计失败不影响主链路）。 */
    private void reportViolation(String resourceType, String resourceId, String resourceTenantId,
                                 String action, String detail) {
        TenantIsolationGuard.report(isolationAuditor, TenantIsolationGuard.violationOf(
                TenantContext.current(), resourceType, resourceId, resourceTenantId, action, detail));
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
