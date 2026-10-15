package com.lingxi.report.domain;

/**
 * 分析事件（M5-5.1）：消费后的领域形态。
 *
 * <p>{@code payloadJson} 保留事件体原文（除已在信封中解析的字段），由应用层按类型解析
 * 各自字段——这样新增事件字段无需改动领域模型。</p>
 *
 * @param eventId   事件 ID（生产端生成一次；主键去重，Kafka 重投幂等）
 * @param type      事件类型
 * @param tenantId  租户 ID（**强制过滤维度**）
 * @param sessionId 会话 ID
 * @param occurredAt 事件发生时间（epoch ms）
 * @param payloadJson 事件体原文（JSON）
 */
public record AnalyticsEvent(String eventId, AnalyticsEventType type, String tenantId,
                             String sessionId, long occurredAt, String payloadJson) {
}
