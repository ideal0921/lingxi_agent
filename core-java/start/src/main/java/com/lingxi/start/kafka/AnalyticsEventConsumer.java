package com.lingxi.start.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.report.application.ReportIngestService;
import com.lingxi.report.domain.AnalyticsEvent;
import com.lingxi.report.domain.AnalyticsEventType;

/**
 * 分析事件消费者（M5-5.1）：消费 {@code lingxi.analytics.event}，
 * 交给报表域幂等落库（明细 + 聚合）。
 *
 * <p>放在 {@code start} 模块是因为 Kafka 依赖在 start；异常一律隔离（单条失败不中断消费），
 * 且**幂等由明细表主键保证**——重投同一 {@code event_id} 时聚合不会重复累加。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class AnalyticsEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventConsumer.class);

    private final ReportIngestService ingestService;
    private final ObjectMapper objectMapper;

    public AnalyticsEventConsumer(ReportIngestService ingestService, ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${lingxi.kafka.topic.analytics-event:lingxi.analytics.event}",
            groupId = "${lingxi.kafka.consumer-group:core-java}")
    public void onAnalyticsEvent(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String eventId = node.path("event_id").asText("");
            AnalyticsEventType type = AnalyticsEventType.fromWire(node.path("type").asText(""));
            if (eventId.isBlank() || type == null) {
                log.warn("忽略非法的分析事件（缺 event_id 或未知 type）: {}", message);
                return;
            }
            ingestService.handle(new AnalyticsEvent(
                    eventId,
                    type,
                    node.path("tenant_id").asText(""),
                    node.path("session_id").asText(""),
                    node.path("occurred_at").asLong(System.currentTimeMillis()),
                    message));
        } catch (Exception e) {
            log.error("处理分析事件失败: {}", message, e);
        }
    }
}
