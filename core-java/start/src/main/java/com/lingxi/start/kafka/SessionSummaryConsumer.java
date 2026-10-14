package com.lingxi.start.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.session.model.Session;
import com.lingxi.session.repo.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 会话摘要消费者（M3-3.11 上下文窗口管理）：AI 域把「超出窗口的旧消息」压缩为摘要后
 * 投递 {@code lingxi.session.summary}，此处回写到会话实体，随下一轮事件再带给 AI 域，
 * 形成「会话摘要 + 近 N 轮 + 本轮知识」的分层上下文。
 *
 * <p>幂等：摘要为覆盖式写入，重复投递只以最后一次为准。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class SessionSummaryConsumer {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryConsumer.class);

    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public SessionSummaryConsumer(SessionRepository sessionRepository, ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${lingxi.kafka.topic.session-summary:lingxi.session.summary}",
            groupId = "${lingxi.kafka.consumer-group:core-java}")
    public void onSummary(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String sessionId = node.path("session_id").asText("");
            String summary = node.path("summary").asText("");
            if (sessionId.isBlank() || summary.isBlank()) {
                log.warn("忽略非法的会话摘要事件 sessionId={}", sessionId);
                return;
            }
            Session session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                log.warn("会话不存在，忽略摘要回写 sessionId={}", sessionId);
                return;
            }
            session.updateSummary(summary);
            sessionRepository.save(session);
            log.info("会话摘要已回写 sessionId={} 长度={}", sessionId, summary.length());
        } catch (Exception e) {
            log.error("处理会话摘要事件失败: {}", message, e);
        }
    }
}
