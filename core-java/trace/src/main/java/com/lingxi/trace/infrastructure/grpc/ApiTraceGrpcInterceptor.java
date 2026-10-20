package com.lingxi.trace.infrastructure.grpc;

import java.util.concurrent.atomic.AtomicBoolean;

import com.lingxi.common.tenant.TenantMetadata;
import com.lingxi.common.trace.TraceContext;
import com.lingxi.common.trace.TraceIds;
import com.lingxi.trace.application.ApiTraceEntry;
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.domain.ApiTraceSpan;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * gRPC 链路拦截器（M5-5.9）：最外层（先于 {@code TenantGuardInterceptor} 注册）。
 *
 * <p>为什么放最外层且直接读 metadata 身份：Trace 必须覆盖**被租户守卫拒绝**的调用——
 * 此时 {@code TenantContext} 尚未绑定，只能从 metadata 取 {@code x-tenant-id/x-user-id/x-role}。</p>
 *
 * <p>记录时机：包装 {@link ServerCall} 在最终 {@code close} 时落 span，从而无论调用成功、
 * 失败还是被守卫拒绝都能记到（被拒绝时守卫调用的正是本包装后的 call）。</p>
 */
public class ApiTraceGrpcInterceptor implements ServerInterceptor {

    private final ApiTraceService service;

    public ApiTraceGrpcInterceptor(ApiTraceService service) {
        this.service = service;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                Metadata headers,
                                                                ServerCallHandler<ReqT, RespT> next) {
        String traceId = TraceIds.normalize(header(headers, TraceIds.HEADER_TRACE_ID));
        String spanId = TraceIds.newSpanId();
        String tenantId = header(headers, TenantMetadata.KEY_TENANT_ID);
        String userId = header(headers, TenantMetadata.KEY_USER_ID);
        String role = header(headers, TenantMetadata.KEY_ROLE);
        String entry = call.getMethodDescriptor().getFullMethodName();
        long startedAt = System.currentTimeMillis();
        // 保证同一调用只记一次 span（close 与 cancel 可能先后触发）
        AtomicBoolean recorded = new AtomicBoolean(false);

        ServerCall<ReqT, RespT> wrappedCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                recordSpan(recorded, traceId, spanId, entry, tenantId, userId, role, startedAt, status);
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> delegate;
        try {
            delegate = next.startCall(wrappedCall, headers);
        } catch (RuntimeException e) {
            // 处理器启动异常（极少）：也记一条 ERROR span，避免链路出现断点
            recordSpan(recorded, traceId, spanId, entry, tenantId, userId, role, startedAt,
                    Status.INTERNAL.withDescription(e.getMessage()));
            throw e;
        }
        return bindTraceContext(delegate, traceId, spanId, recorded, entry, tenantId, userId, role, startedAt);
    }

    /** 包装监听器：每个回调执行期间绑定链路上下文，结束后清理（防 gRPC 线程池复用串号）。 */
    private <ReqT> ServerCall.Listener<ReqT> bindTraceContext(ServerCall.Listener<ReqT> delegate,
                                                             String traceId, String spanId,
                                                             AtomicBoolean recorded, String entry,
                                                             String tenantId, String userId, String role,
                                                             long startedAt) {
        return new ServerCall.Listener<>() {

            @Override
            public void onMessage(ReqT message) {
                // 绑定链路以便业务侧（如 Kafka 生产）透传 trace_id
                withContext(() -> delegate.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                withContext(delegate::onHalfClose);
            }

            @Override
            public void onCancel() {
                // 客户端取消：close 可能不被触发，此处兜底记一条（幂等）
                recordSpan(recorded, traceId, spanId, entry, tenantId, userId, role, startedAt,
                        Status.CANCELLED);
                withContext(delegate::onCancel);
            }

            @Override
            public void onComplete() {
                withContext(delegate::onComplete);
            }

            @Override
            public void onReady() {
                withContext(delegate::onReady);
            }

            private void withContext(Runnable action) {
                TraceContext.set(traceId, spanId);
                try {
                    action.run();
                } finally {
                    TraceContext.clear();
                }
            }
        };
    }

    /** 记录一条 gRPC span：状态按 gRPC Status 记为 OK/ERROR（含被守卫拒绝的调用）。 */
    private void recordSpan(AtomicBoolean recorded, String traceId, String spanId, String entry,
                            String tenantId, String userId, String role, long startedAt, Status status) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        boolean ok = status != null && status.isOk();
        int statusCode = status == null ? 0 : status.getCode().value();
        service.record(ApiTraceEntry.ofServer(traceId, spanId, ApiTraceSpan.COMPONENT_GRPC, entry,
                ok ? ApiTraceSpan.STATUS_OK : ApiTraceSpan.STATUS_ERROR, statusCode,
                tenantId, userId, role, System.currentTimeMillis() - startedAt,
                ok ? "" : description(status), startedAt));
    }

    /** 错误摘要：优先 gRPC 描述，其次状态码名（不含请求体）。 */
    private static String description(Status status) {
        if (status == null) {
            return "";
        }
        String value = status.getDescription();
        return value == null || value.isBlank() ? status.getCode().name() : value;
    }

    /** 读取 metadata 头（缺失或非 ASCII 返回空串）。 */
    private static String header(Metadata headers, String key) {
        if (headers == null) {
            return "";
        }
        String value = headers.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        return value == null ? "" : value;
    }
}
