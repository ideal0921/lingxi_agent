package com.lingxi.trace.domain;

/**
 * API 链路 Span（M5-5.9）：一次组件内调用的最小事实记录（无请求体/响应体，规避 PII 风险）。
 *
 * <p>字段与表 {@code lingxi_api_trace} 一一对应；三端（gateway-go/core-java/ai-python）
 * 共用同一列语义，管理端据此还原完整链路时间线。</p>
 *
 * @param spanId       本段 ID（UUID，主键）
 * @param traceId      链路 ID（网关生成，三端贯穿）
 * @param parentSpanId 上游 span ID（根为空串，用于还原调用关系）
 * @param service      来源服务：固定 core-java（Java 侧只采本服务）
 * @param component    类型：HTTP / GRPC / KAFKA
 * @param entry        入口：HTTP 路径 / gRPC 全限定方法 / Kafka topic
 * @param httpMethod   HTTP 方法（非 HTTP 为空串）
 * @param status       结果：OK / ERROR
 * @param statusCode   状态码（HTTP 状态码 / gRPC 状态码，无则 0）
 * @param tenantId     租户 ID
 * @param userId       主体 ID
 * @param role         主体角色
 * @param clientIp     来源 IP
 * @param elapsedMs    耗时（ms）
 * @param errorMsg     错误摘要（截断，不含请求体）
 * @param startedAtMs  开始时间（epoch ms）
 */
public record ApiTraceSpan(String spanId, String traceId, String parentSpanId, String service,
                           String component, String entry, String httpMethod, String status,
                           int statusCode, String tenantId, String userId, String role,
                           String clientIp, long elapsedMs, String errorMsg, long startedAtMs) {

    /** Java 侧固定服务名（与 gateway-go / ai-python 区分）。 */
    public static final String SERVICE = "core-java";

    /* 组件类型常量（与契约一致）。 */
    public static final String COMPONENT_HTTP = "HTTP";
    public static final String COMPONENT_GRPC = "GRPC";
    public static final String COMPONENT_KAFKA = "KAFKA";

    /* 结果状态常量。 */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
}
