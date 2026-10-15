package com.lingxi.start.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.session.ai.SuggestRequestPublisher;

import lingxi.agentsync.v1.AgentSync.ChatTurn;
import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;

/**
 * 回复建议异步队列 Kafka 生产者（M4-4.6）：同步通道降级时把生成请求转入
 * {@code lingxi.ai.suggest.request}，由 AI 域异步生成后再经坐席端推送。
 *
 * <p>以 {@code session_id} 作为消息键，保证同一会话的请求有序落到同一分区。
 * 仅在 {@code lingxi.kafka.enabled=true} 时装配。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class KafkaSuggestRequestPublisher implements SuggestRequestPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaSuggestRequestPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaSuggestRequestPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                        ObjectMapper objectMapper,
                                        @Value("${lingxi.kafka.topic.ai-suggest-request:lingxi.ai.suggest.request}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public boolean enqueue(GenerateSuggestRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", request.getRequestId());
        payload.put("tenant_id", request.getTenantId());
        payload.put("session_id", request.getSessionId());
        payload.put("agent_id", request.getAgentId());
        payload.put("intent", request.getIntent());
        payload.put("summary", request.getSummary());
        payload.put("history", toHistoryPayload(request.getHistoryList()));
        payload.put("max_suggestions", request.getMaxSuggestions());

        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化失败属编码缺陷：记录后返回 false，让调用方如实回传 queued=false
            log.error("建议请求序列化失败 sessionId={}", request.getSessionId(), e);
            return false;
        }

        try {
            kafkaTemplate.send(topic, request.getSessionId(), json);
            log.debug("建议请求已入队 sessionId={} requestId={} topic={}",
                    request.getSessionId(), request.getRequestId(), topic);
            return true;
        } catch (RuntimeException e) {
            log.error("建议请求投递失败 sessionId={} requestId={}", request.getSessionId(), request.getRequestId(), e);
            return false;
        }
    }

    /** 近 N 轮历史 → 队列体（仅保留角色与内容，避免冗余字段随消息膨胀）。 */
    private static List<Map<String, String>> toHistoryPayload(List<ChatTurn> history) {
        List<Map<String, String>> items = new ArrayList<>();
        for (ChatTurn turn : history) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", turn.getRole());
            item.put("content", turn.getContent());
            items.add(item);
        }
        return items;
    }
}
