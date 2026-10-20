package com.lingxi.trace.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.lingxi.common.trace.TraceContext;
import com.lingxi.common.trace.TraceIds;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantGuardInterceptor;
import com.lingxi.common.tenant.TenantMetadata;
import com.lingxi.common.tenant.TenantQuotaGate;
import com.lingxi.trace.application.ApiTraceBatchWriter;
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.application.RecordingTraceRepository;
import com.lingxi.trace.domain.ApiTraceSpan;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;

/**
 * gRPC 链路拦截器单测（M5-5.9）：租户被拒的调用仍产生 ERROR span、正常调用状态/租户正确。
 */
class ApiTraceGrpcInterceptorTest {

    private static final String SESSION_SERVICE = "lingxi.session.v1.SessionService";

    /** 空实现编组器：本用例不触发编解码，仅需方法描述符。 */
    private static final MethodDescriptor.Marshaller<Object> MARSHALLER = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(Object value) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public Object parse(InputStream stream) {
            return null;
        }
    };

    private ApiTraceBatchWriter writer;

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        TenantContext.clear();
        if (writer != null) {
            writer.shutdown();
        }
    }

    @Test
    void rejectedCallStillProducesErrorSpan() {
        RecordingTraceRepository repository = new RecordingTraceRepository();
        ApiTraceGrpcInterceptor trace = new ApiTraceGrpcInterceptor(newService(repository));
        // 内层装配租户守卫：无租户 metadata 将被拒绝
        TenantGuardInterceptor guard = new TenantGuardInterceptor(Set.of(), new TenantQuotaGate(4, Map.of(), 100));
        RecordingCall call = new RecordingCall(SESSION_SERVICE + "/SendMessage");
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(TraceIds.HEADER_TRACE_ID, Metadata.ASCII_STRING_MARSHALLER), "trace-rejected");

        // 组合出「Trace 在外、TenantGuard 在内」的真实层级
        trace.interceptCall(call, headers, (c, h) -> guard.interceptCall(c, h, (c2, h2) -> new ServerCall.Listener<>() {
        }));

        assertTrue(call.closed, "被守卫拒绝应已关闭调用");
        awaitUntil(() -> repository.spans().size() >= 1);
        ApiTraceSpan span = repository.spans().get(0);
        assertEquals("trace-rejected", span.traceId());
        assertEquals("core-java", span.service());
        assertEquals("GRPC", span.component());
        assertEquals(SESSION_SERVICE + "/SendMessage", span.entry());
        assertEquals("ERROR", span.status(), "被拒调用应记为 ERROR");
        assertEquals(Status.Code.UNAUTHENTICATED.value(), span.statusCode());
    }

    @Test
    void normalCallRecordsOkSpanWithMetadataIdentity() {
        RecordingTraceRepository repository = new RecordingTraceRepository();
        ApiTraceGrpcInterceptor trace = new ApiTraceGrpcInterceptor(newService(repository));
        RecordingCall call = new RecordingCall(SESSION_SERVICE + "/SendMessage");
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(TraceIds.HEADER_TRACE_ID, Metadata.ASCII_STRING_MARSHALLER), "trace-ok");
        headers.put(Metadata.Key.of(TenantMetadata.KEY_TENANT_ID, Metadata.ASCII_STRING_MARSHALLER), "tenant-a");
        headers.put(Metadata.Key.of(TenantMetadata.KEY_USER_ID, Metadata.ASCII_STRING_MARSHALLER), "u-1");
        headers.put(Metadata.Key.of(TenantMetadata.KEY_ROLE, Metadata.ASCII_STRING_MARSHALLER), "agent");

        // 处理器正常完成：直接 close(OK)
        ServerCallHandler<Object, Object> handler = (c, h) -> {
            c.close(Status.OK, new Metadata());
            return new ServerCall.Listener<>() {
            };
        };
        ServerCall.Listener<Object> listener = trace.interceptCall(call, headers, handler);
        assertNotNull(listener);

        awaitUntil(() -> repository.spans().size() >= 1);
        ApiTraceSpan span = repository.spans().get(0);
        assertEquals("OK", span.status());
        assertEquals(0, span.statusCode(), "gRPC OK 状态码为 0");
        // 身份直接取自 metadata（而非 ThreadLocal），证明守卫之外层也能取到
        assertEquals("tenant-a", span.tenantId());
        assertEquals("u-1", span.userId());
        assertEquals("agent", span.role());
        assertTrue(span.elapsedMs() >= 0);
    }

    private ApiTraceService newService(RecordingTraceRepository repository) {
        writer = new ApiTraceBatchWriter(repository, 200, 50, 1000);
        return new ApiTraceService(repository, writer, true);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断");
            }
        }
        throw new AssertionError("等待条件超时");
    }

    /** 记录关闭状态与方法描述的 ServerCall 替身。 */
    private static final class RecordingCall extends ServerCall<Object, Object> {

        private final String fullMethodName;
        private Status closedStatus;
        private boolean closed;

        private RecordingCall(String fullMethodName) {
            this.fullMethodName = fullMethodName;
        }

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(Object message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            this.closedStatus = status;
            this.closed = true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<Object, Object> getMethodDescriptor() {
            return MethodDescriptor.newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(fullMethodName)
                    .setRequestMarshaller(MARSHALLER)
                    .setResponseMarshaller(MARSHALLER)
                    .build();
        }
    }
}
