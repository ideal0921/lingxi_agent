package com.lingxi.start.kafka;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.session.event.SessionEventPublisher;
import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.repo.InMemorySessionRepository;
import com.lingxi.session.state.SessionStateMachine.State;

/**
 * 转人工消费者单测（M4-4.5）：直接调用 {@link HandoffConsumer#handle(String)}，
 * 不连 Kafka，覆盖状态迁移、幂等忽略、非法输入与摘要兜底回写。
 */
class HandoffConsumerTest {

    /** 记录 takeover 发布的事件发布者替身。 */
    private static final class RecordingPublisher implements SessionEventPublisher {
        private final List<TakeoverCall> takeovers = new ArrayList<>();

        @Override
        public void publishUserMessage(String sessionId, String tenantId, String userId, String messageId, long seq,
                                       String content, String summary, List<SessionMessage> history) {
            // 本用例不关心用户消息事件
        }

        @Override
        public void publishTakeover(String sessionId, String reason, String agentId) {
            takeovers.add(new TakeoverCall(sessionId, reason, agentId));
        }
    }

    private record TakeoverCall(String sessionId, String reason, String agentId) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemorySessionRepository repository;
    private RecordingPublisher publisher;
    private HandoffConsumer consumer;

    @BeforeEach
    void setUp() {
        repository = new InMemorySessionRepository();
        publisher = new RecordingPublisher();
        consumer = new HandoffConsumer(repository, publisher, objectMapper);
    }

    @Test
    void initSessionEntersWaitingHumanAndPublishesTakeover() {
        Session session = newSession(State.INIT);

        consumer.handle(handoffJson(session.getId(), "low_confidence", null));

        assertEquals(State.WAITING_HUMAN, repository.findById(session.getId()).orElseThrow().getState());
        assertEquals(1, publisher.takeovers.size(), "迁移后应发布 takeover 事件");
        TakeoverCall takeover = publisher.takeovers.get(0);
        assertEquals(session.getId(), takeover.sessionId());
        assertEquals("handoff_requested", takeover.reason());
        assertEquals("", takeover.agentId());
    }

    @Test
    void aiActiveSessionEntersWaitingHuman() {
        Session session = newSession(State.AI_ACTIVE);

        consumer.handle(handoffJson(session.getId(), "emotion", null));

        assertEquals(State.WAITING_HUMAN, repository.findById(session.getId()).orElseThrow().getState());
        assertEquals(1, publisher.takeovers.size());
    }

    @Test
    void alreadyWaitingHumanIsIdempotentlyIgnored() {
        Session session = newSession(State.WAITING_HUMAN);

        consumer.handle(handoffJson(session.getId(), "user_request", null));

        assertEquals(State.WAITING_HUMAN, repository.findById(session.getId()).orElseThrow().getState());
        assertTrue(publisher.takeovers.isEmpty(), "幂等忽略不应重复发布 takeover");
    }

    @Test
    void closedSessionIsIgnored() {
        Session session = newSession(State.CLOSED);

        consumer.handle(handoffJson(session.getId(), "low_confidence", null));

        assertEquals(State.CLOSED, repository.findById(session.getId()).orElseThrow().getState());
        assertTrue(publisher.takeovers.isEmpty(), "已关闭会话忽略转人工事件");
    }

    @Test
    void unknownSessionIsIgnoredWithoutError() {
        assertDoesNotThrow(() -> consumer.handle(handoffJson("no-such-session", "low_confidence", null)));
        assertTrue(publisher.takeovers.isEmpty());
    }

    @Test
    void invalidJsonIsIgnored() {
        assertDoesNotThrow(() -> consumer.handle("{not-json"));
        assertTrue(publisher.takeovers.isEmpty());
    }

    @Test
    void summaryInPayloadIsPersisted() {
        Session session = newSession(State.INIT);

        consumer.handle(handoffJson(session.getId(), "low_confidence", "用户此前咨询过退货政策"));

        Session stored = repository.findById(session.getId()).orElseThrow();
        assertEquals(State.WAITING_HUMAN, stored.getState());
        assertEquals("用户此前咨询过退货政策", stored.getSummary(), "payload 摘要应兜底回写");
    }

    /** 构造一条转人工事件 JSON（summary 为 null 时不写入）。 */
    private String handoffJson(String sessionId, String reason, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId);
        payload.put("reason", reason);
        payload.put("message_id", "m-1");
        payload.put("seq", 3);
        if (summary != null) {
            payload.put("summary", summary);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测试事件构造失败", e);
        }
    }

    private Session newSession(State state) {
        Session session = new Session("tenant-1", "u1", "web");
        if (state != State.INIT) {
            session.changeState(state);
        }
        return repository.save(session);
    }
}
