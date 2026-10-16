package com.lingxi.common.tenant;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;

/**
 * 租户守卫拦截器单测（M5-5.5）：metadata 解析、缺失即未认证、豁免服务、
 * 配额拒绝、以及「租户上下文按回调线程绑定/清理」这一关键语义。
 */
class TenantGuardInterceptorTest {

    private static final String SESSION_SERVICE = "lingxi.session.v1.SessionService";
    private static final String HEALTH_SERVICE = "lingxi.health.v1.HealthService";

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

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void resolveIdentityReadsMetadata() {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(TenantMetadata.KEY_TENANT_ID, Metadata.ASCII_STRING_MARSHALLER), "tenant-a");
        headers.put(Metadata.Key.of(TenantMetadata.KEY_USER_ID, Metadata.ASCII_STRING_MARSHALLER), "u-1");
        headers.put(Metadata.Key.of(TenantMetadata.KEY_ROLE, Metadata.ASCII_STRING_MARSHALLER), "agent");

        TenantIdentity identity = TenantGuardInterceptor.resolveIdentity(headers);
        assertEquals("tenant-a", identity.tenantId());
        assertEquals("u-1", identity.userId());
        assertEquals("agent", identity.role());
    }

    @Test
    void resolveIdentityWithoutHeadersIsEmpty() {
        assertFalse(TenantGuardInterceptor.resolveIdentity(new Metadata()).hasTenant());
        assertFalse(TenantGuardInterceptor.resolveIdentity(null).hasTenant());
    }

    @Test
    void missingTenantIsRejectedAsUnauthenticated() {
        TenantGuardInterceptor interceptor = new TenantGuardInterceptor(Set.of(HEALTH_SERVICE),
                new TenantQuotaGate(4, Map.of(), 100));
        RecordingCall call = new RecordingCall(SESSION_SERVICE + "/CreateSession");
        AtomicReference<Boolean> handlerInvoked = new AtomicReference<>(false);

        ServerCall.Listener<Object> listener = interceptor.interceptCall(call, new Metadata(),
                (c, headers) -> {
                    handlerInvoked.set(true);
                    return new ServerCall.Listener<>() {
                    };
                });

        assertNotNull(listener);
        assertFalse(handlerInvoked.get(), "缺少租户时不得进入业务处理器");
        assertTrue(call.closed);
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus.getCode());
    }

    @Test
    void exemptServiceRunsWithoutTenant() {
        TenantGuardInterceptor interceptor = new TenantGuardInterceptor(Set.of(HEALTH_SERVICE),
                new TenantQuotaGate(1, Map.of(), 100));
        RecordingCall call = new RecordingCall(HEALTH_SERVICE + "/Ping");
        AtomicReference<Boolean> handlerInvoked = new AtomicReference<>(false);

        interceptor.interceptCall(call, new Metadata(), (c, headers) -> {
            handlerInvoked.set(true);
            return new ServerCall.Listener<>() {
            };
        });

        assertTrue(handlerInvoked.get(), "豁免服务（健康检查）不应因缺租户被拒");
        assertFalse(call.closed);
        assertNull(call.closedStatus);
    }

    @Test
    void quotaExceededIsRejectedAsResourceExhausted() {
        TenantQuotaGate gate = new TenantQuotaGate(1, Map.of(), 100);
        TenantGuardInterceptor interceptor = new TenantGuardInterceptor(Set.of(), gate);
        Metadata headers = tenantHeaders("tenant-a");

        // 先占满配额（不释放），第二次调用应被拒
        interceptor.interceptCall(new RecordingCall(SESSION_SERVICE + "/SendMessage"), headers,
                (c, h) -> new ServerCall.Listener<>() {
                });
        RecordingCall second = new RecordingCall(SESSION_SERVICE + "/SendMessage");
        interceptor.interceptCall(second, headers, (c, h) -> new ServerCall.Listener<>() {
        });

        assertTrue(second.closed);
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, second.closedStatus.getCode());
        assertEquals(1, gate.inFlightOf("tenant-a"), "被拒调用不得占用配额");
    }

    @Test
    void contextIsBoundDuringCallbackAndClearedAfter() {
        TenantGuardInterceptor interceptor = new TenantGuardInterceptor(Set.of(),
                new TenantQuotaGate(4, Map.of(), 100));
        RecordingCall call = new RecordingCall(SESSION_SERVICE + "/SendMessage");
        AtomicReference<String> seenInCallback = new AtomicReference<>();

        ServerCall.Listener<Object> listener = interceptor.interceptCall(call, tenantHeaders("tenant-a"),
                (c, headers) -> new ServerCall.Listener<>() {
                    @Override
                    public void onHalfClose() {
                        seenInCallback.set(TenantContext.currentTenantId());
                    }
                });

        listener.onHalfClose();

        assertEquals("tenant-a", seenInCallback.get(), "回调执行期间应绑定租户上下文");
        assertThrows(MissingTenantContextException.class, TenantContext::requireTenantId,
                "回调结束后必须清理，防 gRPC 线程池复用导致租户串号");
    }

    /** 构造携带租户 metadata 的请求头。 */
    private static Metadata tenantHeaders(String tenantId) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of(TenantMetadata.KEY_TENANT_ID, Metadata.ASCII_STRING_MARSHALLER), tenantId);
        return headers;
    }
}
