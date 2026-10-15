package com.lingxi.start.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.session.event.AnalyticsEventPublisher;

/**
 * 分析事件 Kafka 生产者（M5-5.1）：把会话生命周期与满意度埋点投递到
 * {@code lingxi.analytics.event}，由报表域消费后落分析表。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>以 {@code session_id} 作为消息键——同一会话的事件有序落到同一分区；</li>
 *   <li>{@code event_id} 由生产端生成一次：Kafka 至少一次语义下重投同 ID，
 *       消费侧以主键去重，聚合不会因重投而虚高；</li>
 *   <li>序列化/投递失败**仅记录日志、不抛出**：埋点属旁路，绝不拖垮会话主链路。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class KafkaAnalyticsEventPublisher implements AnalyticsEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaAnalyticsEventPublisher.class);

    private static final String TYPE_LIFECYCLE = "session_lifecycle";
    private static final String TYPE_FEEDBACK = "session_feedback";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaAnalyticsEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                        ObjectMapper objectMapper,
                                        @Value("${lingxi.kafka.topic.analytics-event:lingxi.analytics.event}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publishLifecycle(String sessionId, String tenantId, String stage, String channel,
                                 String userId, String agentId, String messageId) {
        Map<String, Object> payload = envelope(TYPE_LIFECYCLE, sessionId, tenantId);
        payload.put("stage", stage == null ? "" : stage);
        payload.put("channel", channel == null ? "" : channel);
        payload.put("user_id", userId == null ? "" : userId);
        payload.put("agent_id", agentId == null ? "" : agentId);
        payload.put("message_id", messageId == null ? "" : messageId);
        send(sessionId, payload, "生命周期埋点 stage=" + stage);
    }

    @Override
    public void publishFeedback(String sessionId, String tenantId, String userId, int rating,
                               String comment, String tags) {
        Map<String, Object> payload = envelope(TYPE_FEEDBACK, sessionId, tenantId);
        payload.put("user_id", userId == null ? "" : userId);
        payload.put("rating", rating);
        payload.put("comment", comment == null ? "" : comment);
        payload.put("tags", tags == null ? "" : tags);
        send(sessionId, payload, "满意度埋点 rating=" + rating);
    }

    /** 统一信封：事件 ID（重投去重键）+ 类型 + 归属 + 发生时间。 */
    private static Map<String, Object> envelope(String type, String sessionId, String tenantId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("type", type);
        payload.put("tenant_id", tenantId == null ? "" : tenantId);
        payload.put("session_id", sessionId == null ? "" : sessionId);
        payload.put("occurred_at", System.currentTimeMillis());
        return payload;
    }

    /** 序列化并投递；任何失败都只记日志，不影响调用方（会话主链路）。 */
    private void send(String sessionId, Map<String, Object> payload, String description) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("分析事件序列化失败 sessionId={} {}", sessionId, description, e);
            return;
        }
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, sessionId, json);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("分析事件投递失败 sessionId={} {}（埋点丢失不阻断主链路）", sessionId, description, ex);
            } else {
                log.debug("分析事件已投递 sessionId={} {} partition={}", sessionId, description,
                        result.getRecordMetadata().partition());
            }
        });
    }
}
