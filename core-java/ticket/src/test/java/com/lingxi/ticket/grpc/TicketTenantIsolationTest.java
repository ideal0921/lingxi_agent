package com.lingxi.ticket.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.common.tenant.IsolationViolation;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;
import com.lingxi.common.tenant.TenantIsolationAuditor;
import com.lingxi.ticket.model.Ticket;
import com.lingxi.ticket.repo.InMemoryTicketRepository;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lingxi.ticket.v1.TicketOuterClass;

/**
 * 工单域租户隔离单测（M5-5.5）：跨租户流转被拒且落审计、列表按租户过滤、
 * 伪造租户被拒、可信租户为唯一归属来源。
 */
class TicketTenantIsolationTest {

    private static final String TENANT = "tenant-1";
    private static final String OTHER_TENANT = "tenant-2";

    private InMemoryTicketRepository repository;
    private RecordingIsolationAuditor auditor;
    private TicketServiceGrpcImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTicketRepository();
        auditor = new RecordingIsolationAuditor();
        service = new TicketServiceGrpcImpl(repository, auditor);
        TenantContext.set(new TenantIdentity(TENANT, "agent-1", "agent"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createTicketRejectsForgedTenantAndAudits() {
        CapturingObserver<TicketOuterClass.CreateTicketResponse> observer = new CapturingObserver<>();
        service.createTicket(TicketOuterClass.CreateTicketRequest.newBuilder()
                .setTenantId(OTHER_TENANT).setSessionId("s-1").setUserId("u-1")
                .setAgentId("agent-1").setTitle("退款问题").build(), observer);

        assertNull(observer.value, "伪造租户不得建单");
        assertEquals(Status.Code.PERMISSION_DENIED, statusCodeOf(observer.error));
        assertEquals(1, auditor.violations.size());
        assertEquals("create_ticket", auditor.violations.get(0).action());
    }

    @Test
    void createTicketUsesTrustedTenant() {
        CapturingObserver<TicketOuterClass.CreateTicketResponse> observer = new CapturingObserver<>();
        // 请求体不传 tenant_id：归属一律取可信上下文
        service.createTicket(TicketOuterClass.CreateTicketRequest.newBuilder()
                .setSessionId("s-1").setUserId("u-1").setAgentId("agent-1")
                .setTitle("退款问题").build(), observer);

        assertEquals(TENANT, observer.value.getTicket().getTenantId(), "工单归属应取可信租户");
        assertTrue(auditor.violations.isEmpty());
        assertTrue(repository.findById(observer.value.getTicket().getTicketId(), TENANT).isPresent());
    }

    @Test
    void crossTenantStatusUpdateIsDeniedAndAudited() {
        Ticket ticket = repository.save(new Ticket(TENANT, "s-1", "u-1", "agent-1", "退款问题", ""));
        TenantContext.set(new TenantIdentity(OTHER_TENANT, "agent-2", "agent"));

        CapturingObserver<TicketOuterClass.UpdateTicketStatusResponse> observer = new CapturingObserver<>();
        service.updateTicketStatus(TicketOuterClass.UpdateTicketStatusRequest.newBuilder()
                .setTicketId(ticket.getTicketId())
                .setTargetStatus(TicketOuterClass.TicketStatus.PROCESSING)
                .setOperator("agent-2").build(), observer);

        assertNull(observer.value, "越权不得流转工单");
        assertEquals(Status.Code.NOT_FOUND, statusCodeOf(observer.error), "对外按不存在返回，不泄露存在性");
        assertEquals(1, auditor.violations.size(), "越权应落隔离审计");
        assertEquals(ticket.getTicketId(), auditor.violations.get(0).resourceId());
        // 工单状态未被越权修改
        assertEquals(com.lingxi.ticket.model.TicketStatus.CREATED,
                repository.findById(ticket.getTicketId(), TENANT).orElseThrow().getStatus());
    }

    @Test
    void listTicketsIsFilteredByCallerTenant() {
        repository.save(new Ticket(TENANT, "s-1", "u-1", "agent-1", "本租户工单", ""));
        repository.save(new Ticket(OTHER_TENANT, "s-2", "u-2", "agent-1", "他租户工单", ""));

        CapturingObserver<TicketOuterClass.ListTicketsResponse> observer = new CapturingObserver<>();
        service.listTickets(TicketOuterClass.ListTicketsRequest.newBuilder()
                .setAgentId("agent-1").build(), observer);

        assertEquals(1, observer.value.getTicketsCount(), "列表应只含调用方租户工单");
        assertEquals(TENANT, observer.value.getTickets(0).getTenantId());
    }

    @Test
    void missingTenantContextIsUnauthenticated() {
        TenantContext.clear();
        CapturingObserver<TicketOuterClass.ListTicketsResponse> observer = new CapturingObserver<>();

        service.listTickets(TicketOuterClass.ListTicketsRequest.newBuilder().build(), observer);

        assertEquals(Status.Code.UNAUTHENTICATED, statusCodeOf(observer.error));
    }

    @Test
    void unknownTicketIsNotFoundWithoutAudit() {
        CapturingObserver<TicketOuterClass.UpdateTicketStatusResponse> observer = new CapturingObserver<>();
        service.updateTicketStatus(TicketOuterClass.UpdateTicketStatusRequest.newBuilder()
                .setTicketId("no-such-ticket")
                .setTargetStatus(TicketOuterClass.TicketStatus.PROCESSING).build(), observer);

        assertEquals(Status.Code.NOT_FOUND, statusCodeOf(observer.error));
        assertTrue(auditor.violations.isEmpty(), "仅「不存在」不应误报越权审计");
    }

    /** gRPC 错误码提取（无错误时返回 UNKNOWN，便于断言失败时给出可读结果）。 */
    private static Status.Code statusCodeOf(Throwable error) {
        if (error instanceof StatusRuntimeException runtimeException) {
            return runtimeException.getStatus().getCode();
        }
        return Status.Code.UNKNOWN;
    }

    /** 收集 gRPC 应答的观察者替身。 */
    private static final class CapturingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;

        @Override
        public void onNext(T next) {
            this.value = next;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            // 用例只关心结果与错误，无需处理完成事件
        }
    }

    /** 记录型隔离审计替身。 */
    private static final class RecordingIsolationAuditor implements TenantIsolationAuditor {

        private final List<IsolationViolation> violations = new ArrayList<>();

        @Override
        public void record(IsolationViolation violation) {
            violations.add(violation);
        }
    }
}
