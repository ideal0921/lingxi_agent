package com.lingxi.common.trace;

import java.util.UUID;

/**
 * 链路 ID 约定（M5-5.9）：HTTP header 与 gRPC metadata 统一使用 {@code x-trace-id}，
 * 使三端（gateway-go / core-java / ai-python）在同一 key 上传递链路标识，避免跨语言命名歧义。
 */
public final class TraceIds {

    /** 入站/出站 trace 载体键：HTTP header 与 gRPC metadata 同名（三端一致）。 */
    public static final String HEADER_TRACE_ID = "x-trace-id";

    /** 响应头回写键：网关与 Java 回写，便于前端/排障时直接取用。 */
    public static final String RESPONSE_HEADER_TRACE_ID = "X-Trace-Id";

    private TraceIds() {
    }

    /** 生成新的 traceId（UUID）。 */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** 生成新的 spanId（UUID，标识一次组件内调用）。 */
    public static String newSpanId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 规范化入站 traceId：空白则生成新的，保证全链路必有 traceId（链路起点无上游时自建）。
     */
    public static String normalize(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return generate();
        }
        return traceId.trim();
    }
}
