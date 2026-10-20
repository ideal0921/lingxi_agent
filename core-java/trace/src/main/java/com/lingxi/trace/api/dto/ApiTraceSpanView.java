package com.lingxi.trace.api.dto;

import com.lingxi.trace.domain.ApiTraceSpan;

/**
 * 链路 Span JSON 视图（M5-5.9）：管理后台「接口链路追踪」页展示字段（camelCase，与前端契约一致）。
 */
public record ApiTraceSpanView(String spanId, String traceId, String parentSpanId, String service,
                               String component, String entry, String httpMethod, String status,
                               int statusCode, String tenantId, String userId, String role,
                               String clientIp, long elapsedMs, String errorMsg, long startedAtMs) {

    /** 领域 span → 视图。 */
    public static ApiTraceSpanView from(ApiTraceSpan span) {
        return new ApiTraceSpanView(span.spanId(), span.traceId(), span.parentSpanId(), span.service(),
                span.component(), span.entry(), span.httpMethod(), span.status(), span.statusCode(),
                span.tenantId(), span.userId(), span.role(), span.clientIp(), span.elapsedMs(),
                span.errorMsg(), span.startedAtMs());
    }
}
