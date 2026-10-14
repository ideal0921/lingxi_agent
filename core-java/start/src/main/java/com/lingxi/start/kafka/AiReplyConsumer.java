package com.lingxi.start.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.repo.SessionRepository;

/**
 * AI 最终回答消费者（M3-3.8）：把 AI 生成结果作为助手消息落库到会话。
 *
 * <p>落库后，断连续传（resume → PullMessages）与历史回源即可取到 AI 回答；
 * 消息 ID 沿用上游生成的 {@code ai-<uuid>}，前端据此与流式内容归并为同一条，
 * 避免打字机内容与落库消息重复渲染。</p>
 *
 * <p>幂等：重复投递同一 {@code message_id} 时跳过落库（Kafka 至少一次语义兜底）。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class AiReplyConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiReplyConsumer.class);

    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public AiReplyConsumer(SessionRepository sessionRepository, ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${lingxi.kafka.topic.ai-reply:lingxi.ai.reply}",
            groupId = "${lingxi.kafka.consumer-group:core-java}")
    public void onAiReply(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String sessionId = node.path("session_id").asText("");
            String messageId = node.path("message_id").asText("");
            String content = node.path("content").asText("");
            if (sessionId.isBlank() || content.isBlank()) {
                log.warn("忽略非法的 AI 回答事件 sessionId={} messageId={}", sessionId, messageId);
                return;
            }
            sessionRepository.findById(sessionId).ifPresentOrElse(
                    session -> persist(session, sessionId, messageId, content),
                    () -> log.warn("会话不存在，忽略 AI 回答 sessionId={}", sessionId));
        } catch (Exception e) {
            log.error("处理 AI 回答事件失败: {}", message, e);
        }
    }

    /** 落库助手消息（含幂等校验）。 */
    private void persist(Session session, String sessionId, String messageId, String content) {
        if (session.hasMessageId(messageId)) {
            log.debug("AI 回答已落库，跳过重复事件 sessionId={} messageId={}", sessionId, messageId);
            return;
        }
        SessionMessage saved = session.appendMessage("agent", "ai", content, messageId);
        log.info("AI 回答已落库 sessionId={} messageId={} seq={}", sessionId, messageId, saved.seq());
    }
}
