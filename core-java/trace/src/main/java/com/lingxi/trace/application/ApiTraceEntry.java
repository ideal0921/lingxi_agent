package com.lingxi.trace.application;

import com.lingxi.common.security.RequestContext;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;
import com.lingxi.trace.domain.ApiTraceSpan;

/**
 * 待落库的链路 span 条目（M5-5.9）：组件侧只提供「组件/入口/方法/结果/耗时/开始时间」，
 * 租户身份与来源 IP 由可信上下文（{@link TenantContext}/{@link RequestContext}）自动补全，
 * 避免各处手工拼装遗漏租户——**管理端按租户强过滤**，缺租户的 span 将查不到。
 *
 * @param traceId      链路 ID
 * @param spanId       本段 span ID
 * @param parentSpanId 上游 span ID（根为空串）
 * @param component    类型：HTTP / GRPC / KAFKA
 * @param entry        入口：HTTP 路径 / gRPC 全限定方法 / Kafka topic
 * @param httpMethod   HTTP 方法（非 HTTP 为空串）
 * @param status       结果：OK / ERROR
 * @param statusCode   状态码（HTTP / gRPC，无则 0）
 * @param tenantId     租户 ID
 * @param userId       主体 ID
 * @param role         角色
 * @param clientIp     来源 IP
 * @param elapsedMs    耗时（ms）
 * @param errorMsg     错误摘要（截断，不含请求体/响应体）
 * @param startedAtMs  开始时间（epoch ms）
 */
public record ApiTraceEntry(String traceId, String spanId, String parentSpanId, String component,
                            String entry, String httpMethod, String status, int statusCode,
                            String tenantId, String userId, String role, String clientIp,
                            long elapsedMs, String errorMsg, long startedAtMs) {

    /**
     * HTTP 入口条目：租户身份取自 {@link TenantContext}（认证拦截器已绑定），
     * 来源 IP 取自 {@link RequestContext}（请求元信息过滤器已绑定），根 span 无上游。
     *
     * <p>未认证请求（登录等豁免鉴权的路径）没有租户上下文，回落 {@code fallbackTenantId}（默认租户），
     * 与 {@code AuthService.login} 的既有约定一致——否则租户为空的 span 会被管理端的租户强过滤排除。</p>
     */
    public static ApiTraceEntry ofHttp(String traceId, String spanId, String entry, String httpMethod,
                                       String status, int statusCode, long elapsedMs, String errorMsg,
                                       long startedAtMs, String fallbackTenantId) {
        TenantIdentity identity = TenantContext.current();
        String contextTenant = identity.tenantId() == null ? "" : identity.tenantId();
        String tenantId = contextTenant.isBlank() ? text(fallbackTenantId) : contextTenant;
        return new ApiTraceEntry(text(traceId), text(spanId), "", ApiTraceSpan.COMPONENT_HTTP, entry,
                text(httpMethod), status, Math.max(statusCode, 0), tenantId, identity.userId(),
                identity.role(), RequestContext.current().clientIp(), Math.max(elapsedMs, 0),
                text(errorMsg), startedAtMs);
    }

    /**
     * 服务端入口条目（gRPC / Kafka）：身份来自 metadata 或消息体（此两处不在租户守卫的
     * ThreadLocal 覆盖范围内），故显式传入而非读 {@link TenantContext}。
     */
    public static ApiTraceEntry ofServer(String traceId, String spanId, String component, String entry,
                                         String status, int statusCode, String tenantId, String userId,
                                         String role, long elapsedMs, String errorMsg, long startedAtMs) {
        return new ApiTraceEntry(text(traceId), text(spanId), "", component, entry, "", status,
                Math.max(statusCode, 0), text(tenantId), text(userId), text(role), "",
                Math.max(elapsedMs, 0), text(errorMsg), startedAtMs);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
