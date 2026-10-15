package com.lingxi.start.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.session.event.SessionEventPublisher;
import com.lingxi.session.model.Session;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.session.state.IllegalStateTransitionException;
import com.lingxi.session.state.SessionStateMachine;

/**
 * AI 转人工请求消费者（M4-4.5 人工接管联动）：AI 侧判定「低置信度/用户明示/情绪」后
 * 投递 {@code lingxi.ai.handoff}，此处把会话迁入 {@code WAITING_HUMAN}——仍在客服队列、
 * 但 AI 退出不再应答，随后通知坐席接入。
 *
 * <p>幂等：会话已是 WAITING_HUMAN 或 CLOSED 时重复投递只忽略，不重复迁移、不抛错。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class HandoffConsumer {

    private static final Logger log = LoggerFactory.getLogger(HandoffConsumer.class);

    private final SessionRepository sessionRepository;
    private final SessionEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public HandoffConsumer(SessionRepository sessionRepository, SessionEventPublisher eventPublisher,
                           ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${lingxi.kafka.topic.ai-handoff:lingxi.ai.handoff}",
            groupId = "${lingxi.kafka.consumer-group:core-java}")
    public void handle(String message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("忽略非法的转人工事件: {}", message);
            return;
        }
        String sessionId = node.path("session_id").asText("");
        if (sessionId.isBlank()) {
            log.warn("忽略非法的转人工事件（缺少 session_id）: {}", message);
            return;
        }

        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("会话不存在，忽略转人工事件 sessionId={}", sessionId);
            return;
        }

        SessionStateMachine.State state = session.getState();
        if (state == SessionStateMachine.State.WAITING_HUMAN
                || state == SessionStateMachine.State.CLOSED) {
            log.info("会话 {} 已处于 {}，幂等忽略转人工事件", sessionId, state);
            return;
        }

        // 兜底：AI 侧随事件带来摘要时回写（正常链路摘要由 session.summary 主题单独回写）
        String summary = node.path("summary").asText("");
        if (!summary.isBlank()) {
            session.updateSummary(summary);
        }
        try {
            session.changeState(SessionStateMachine.State.WAITING_HUMAN);
        } catch (IllegalStateTransitionException e) {
            // 例如坐席已接管（HUMAN_CHAT）后转人工事件到达：忽略，不打断人工会话
            log.warn("会话 {} 当前 {} 无法转入 WAITING_HUMAN，忽略转人工事件", sessionId, state);
            return;
        }
        sessionRepository.save(session);
        log.info("会话 {} 进入 WAITING_HUMAN reason={}", sessionId, node.path("reason").asText(""));

        // 通知 AI 域终止该会话正在生成的回答（无坐席，agentId 传空串）
        eventPublisher.publishTakeover(sessionId, "handoff_requested", "");
    }
}
