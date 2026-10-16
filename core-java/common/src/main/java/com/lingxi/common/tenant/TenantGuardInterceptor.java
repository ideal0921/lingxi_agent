package com.lingxi.common.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.Set;

/**
 * 租户守卫拦截器（M5-5.5）：可信租户注入 + 强制校验 + 租户级并发配额，三件事一体化完成，
 * 避免多个拦截器之间的执行顺序依赖。
 *
 * <p>规则：</p>
 * <ol>
 *   <li><b>注入</b>：从 gRPC metadata 读取 {@code x-tenant-id/x-user-id/x-role} 并绑定到
 *       {@link TenantContext}；绑定与清理都在**回调执行线程**上完成（gRPC 回调线程与
 *       {@code interceptCall} 线程不保证同一线程）。</li>
 *   <li><b>强制</b>：业务服务缺少租户标识时拒绝为 {@code UNAUTHENTICATED}；
 *       健康检查等服务按 {@code exemptServices} 豁免（跨语言健康探测无租户语义）。</li>
 *   <li><b>配额</b>：按租户独占并发槽位（大租户独立资源组），闸门满立即
 *       {@code RESOURCE_EXHAUSTED}，不排队。</li>
 * </ol>
 */
public class TenantGuardInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantGuardInterceptor.class);

    private final Set<String> exemptServices;
    private final TenantQuotaGate quotaGate;

    public TenantGuardInterceptor(Set<String> exemptServices, TenantQuotaGate quotaGate) {
        this.exemptServices = exemptServices == null ? Set.of() : Set.copyOf(exemptServices);
        this.quotaGate = quotaGate;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                Metadata headers,
                                                                ServerCallHandler<ReqT, RespT> next) {
        String serviceName = call.getMethodDescriptor().getServiceName();
        TenantIdentity identity = resolveIdentity(headers);
        boolean exempt = exemptServices.contains(serviceName);

        if (!exempt && !identity.hasTenant()) {
            // 业务服务必须携带可信租户：缺失即未认证，直接拒绝并告警（可能是绕过网关直连）
            log.warn("gRPC 调用缺少租户上下文已拒绝 service={} method={}",
                    serviceName, call.getMethodDescriptor().getFullMethodName());
            call.close(Status.UNAUTHENTICATED.withDescription("缺少租户上下文（x-tenant-id）"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        String quotaTenantId = null;
        if (!exempt) {
            if (!quotaGate.tryAcquire(identity.tenantId())) {
                call.close(Status.RESOURCE_EXHAUSTED
                        .withDescription("租户并发已达配额：" + identity.tenantId()), new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
            quotaTenantId = identity.tenantId();
        }

        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
        return bindContext(delegate, identity, quotaTenantId);
    }

    /**
     * 从 metadata 解析租户身份（纯函数，便于单测）。
     *
     * @return 解析结果；租户缺失时 {@link TenantIdentity#hasTenant()} 为 false
     */
    public static TenantIdentity resolveIdentity(Metadata headers) {
        if (headers == null) {
            return TenantIdentity.NONE;
        }
        return new TenantIdentity(
                headers.get(Metadata.Key.of(TenantMetadata.KEY_TENANT_ID, Metadata.ASCII_STRING_MARSHALLER)),
                headers.get(Metadata.Key.of(TenantMetadata.KEY_USER_ID, Metadata.ASCII_STRING_MARSHALLER)),
                headers.get(Metadata.Key.of(TenantMetadata.KEY_ROLE, Metadata.ASCII_STRING_MARSHALLER)));
    }

    /**
     * 包装监听器：每个回调执行期间绑定租户上下文，结束后清理；
     * 并在 RPC 终结（complete/cancel）时归还并发配额（幂等，仅归还一次）。
     *
     * @param quotaTenantId 本次调用占用的配额租户；为 null 表示未占用配额（豁免服务）
     */
    private <ReqT> ServerCall.Listener<ReqT> bindContext(ServerCall.Listener<ReqT> delegate,
                                                         TenantIdentity identity,
                                                         String quotaTenantId) {
        return new ServerCall.Listener<>() {

            @Override
            public void onMessage(ReqT message) {
                withContext(() -> delegate.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                withContext(delegate::onHalfClose);
            }

            @Override
            public void onCancel() {
                withContext(() -> {
                    try {
                        delegate.onCancel();
                    } finally {
                        releaseQuota();
                    }
                });
            }

            @Override
            public void onComplete() {
                withContext(() -> {
                    try {
                        delegate.onComplete();
                    } finally {
                        releaseQuota();
                    }
                });
            }

            @Override
            public void onReady() {
                withContext(delegate::onReady);
            }

            private void withContext(Runnable action) {
                TenantContext.set(identity);
                try {
                    action.run();
                } finally {
                    // 线程可能被 gRPC 线程池复用：必须清理，防止租户串号
                    TenantContext.clear();
                }
            }

            private void releaseQuota() {
                if (quotaTenantId != null) {
                    // TenantQuotaGate 内部有在途计数保护，重复释放不会透支信号量
                    quotaGate.release(quotaTenantId);
                }
            }
        };
    }
}
