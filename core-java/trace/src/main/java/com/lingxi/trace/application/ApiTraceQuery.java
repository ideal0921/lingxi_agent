package com.lingxi.trace.application;

/**
 * 链路 Span 查询条件（M5-5.9）：租户必填（隔离），其余可选。
 *
 * @param tenantId 租户标识（必填，来自可信租户上下文）
 * @param traceId  链路 ID 筛选
 * @param entry    入口筛选（HTTP 路径 / gRPC 方法 / Kafka topic）
 * @param status   结果筛选：OK / ERROR
 * @param fromMs   起始时间（epoch ms，0 表示不限）
 * @param toMs     结束时间（epoch ms，0 表示不限）
 */
public record ApiTraceQuery(String tenantId, String traceId, String entry, String status,
                            long fromMs, long toMs) {

    /** 空字符串归一化为「不限」，避免把空串当成精确匹配。 */
    public ApiTraceQuery {
        traceId = blankToNull(traceId);
        entry = blankToNull(entry);
        status = blankToNull(status);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
