package com.lingxi.start.kafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.lingxi.common.trace.TraceContext;
import com.lingxi.session.event.SessionEventPublisher;
import com.lingxi.session.model.SessionMessage;

/**
 * 会话事件 Kafka 生产者（M3-3.8；M3-3.11 携上下文窗口）：
 * 把用户消息 + 近 N 轮历史 + 会话摘要投递到 {@code lingxi.session.message}，
 * 并携带用户身份（user_id）供 AI 域隔离用户级长期记忆，
 * 由 AI 域消费并流式应答。仅在 {@code lingxi.kafka.enabled=true} 时装配。
 *
 * <p>以 {@code session_id} 作为消息键，保证同一会话的事件有序落到同一分区。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class KafkaSessionEventPublisher implements SessionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaSessionEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String takeoverTopic;

    public KafkaSessionEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                      ObjectMapper objectMapper,
                                      @Value("${lingxi.kafka.topic.session-message:lingxi.session.message}") String topic,
                                      @Value("${lingxi.kafka.topic.session-takeover:lingxi.session.takeover}") String takeoverTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.takeoverTopic = takeoverTopic;
    }

    @Override
    public void publishUserMessage(String sessionId, String tenantId, String userId, String messageId, long seq,
                                   String content, String summary, List<SessionMessage> history) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // 链路透传（M5-5.9）：把当前 traceId 注入消息体，AI 域消费时可续接同一条链路
        payload.put("trace_id", TraceContext.traceId());
        payload.put("session_id", sessionId);
        payload.put("tenant_id", tenantId == null ? "" : tenantId);
        payload.put("user_id", userId == null ? "" : userId);
        payload.put("message_id", messageId);
        payload.put("seq", seq);
        payload.put("content", content);
        // 上下文窗口（M3-3.11）：长程摘要 + 近 N 轮历史，AI 域据此拼装提示词
        payload.put("summary", summary == null ? "" : summary);
        payload.put("history", toHistoryPayload(history));

        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化失败属于编码缺陷，记录后不抛出，避免拖垮 gRPC 应答链路
            log.error("用户消息事件序列化失败 sessionId={}", sessionId, e);
            return;
        }

        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, sessionId, json);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // 发送失败不回滚已落库消息：AI 应答降级为「本次不触发」，不丢用户消息
                log.error("用户消息事件投递失败 sessionId={} seq={}", sessionId, seq, ex);
            } else {
                log.debug("用户消息事件已投递 sessionId={} seq={} 历史条数={} partition={}", sessionId, seq,
                        history == null ? 0 : history.size(), result.getRecordMetadata().partition());
            }
        });
    }

    @Override
    public void publishTakeover(String sessionId, String reason, String agentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId);
        payload.put("reason", reason == null ? "" : reason);
        payload.put("agent_id", agentId == null ? "" : agentId);
        payload.put("at_ms", System.currentTimeMillis());

        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化失败属于编码缺陷，记录后不抛出，避免拖垮 gRPC 应答链路
            log.error("接管事件序列化失败 sessionId={} reason={}", sessionId, reason, e);
            return;
        }

        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(takeoverTopic, sessionId, json);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // 投递失败不回滚已完成的接管：AI 侧未能即时终止，属尽力而为
                log.error("接管事件投递失败 sessionId={} reason={}", sessionId, reason, ex);
            } else {
                log.debug("接管事件已投递 sessionId={} reason={} partition={}", sessionId, reason,
                        result.getRecordMetadata().partition());
            }
        });
    }

    /** 近 N 轮历史 → 事件体（仅保留角色与内容，避免冗余字段随事件膨胀）。 */
    private static List<Map<String, String>> toHistoryPayload(List<SessionMessage> history) {
        List<Map<String, String>> items = new ArrayList<>();
        if (history == null) {
            return items;
        }
        for (SessionMessage message : history) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.sender());
            item.put("content", message.content());
            items.add(item);
        }
        return items;
    }
}
