package com.lingxi.session.grpc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.session.ai.NoOpSuggestRepliesPort;
import com.lingxi.session.ai.NoOpSuggestRequestPublisher;
import com.lingxi.session.ai.SuggestReplyCoordinator;
import com.lingxi.session.event.SessionEventPublisher;
import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.repo.InMemorySessionRepository;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.session.state.SessionStateMachine;

import io.grpc.stub.StreamObserver;

/**
 * 会话域事件发布与上下文窗口单测（M3-3.8 / M3-3.11）：
 * 验证用户消息触发事件、坐席回复不触发、AI 回答按消息 ID 幂等落库，
 * 以及 AI_ACTIVE 状态迁移、近 N 轮历史与摘要随事件透传、接管后 AI 退出。
 */
class SessionEventPublishTest {

    /** 记录发布调用的事件发布者替身。 */
    private static final class RecordingPublisher implements SessionEventPublisher {
        private final List<PublishedEvent> events = new ArrayList<>();
        private final List<TakeoverEvent> takeovers = new ArrayList<>();

        @Override
        public void publishUserMessage(String sessionId, String tenantId, String userId, String messageId, long seq,
                                       String content, String summary, List<SessionMessage> history) {
            events.add(new PublishedEvent(sessionId, tenantId, userId, messageId, seq, content, summary, history));
        }

        @Override
        public void publishTakeover(String sessionId, String reason, String agentId) {
            takeovers.add(new TakeoverEvent(sessionId, reason, agentId));
        }
    }

    private record PublishedEvent(String sessionId, String tenantId, String userId, String messageId, long seq,
                                  String content, String summary, List<SessionMessage> history) {
    }

    /** 记录的接管事件（M4-4.5）。 */
    private record TakeoverEvent(String sessionId, String reason, String agentId) {
    }

    /** 收集 gRPC 应答的观察者替身。 */
    private static final class CapturingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T next) {
            this.value = next;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }

    private RecordingPublisher publisher;
    private SessionRepository repository;
    private SessionServiceGrpcImpl service;
    private String sessionId;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        repository = new InMemorySessionRepository();
        // M4-4.6：快捷回复走离线空实现（本用例不校验快捷回复链路）
        service = new SessionServiceGrpcImpl(repository, publisher, 20, 200,
                new SuggestReplyCoordinator(repository, new NoOpSuggestRepliesPort(),
                        new NoOpSuggestRequestPublisher(), 20));

        CapturingObserver<lingxi.session.v1.Session.CreateSessionResponse> observer = new CapturingObserver<>();
        service.createSession(
                lingxi.session.v1.Session.CreateSessionRequest.newBuilder()
                        .setTenantId("tenant-1").setUserId("u1").setChannel("web").build(),
                observer);
        sessionId = observer.value.getSessionId();
    }

    @Test
    void userMessagePublishesEventAndEntersAiActive() {
        CapturingObserver<lingxi.session.v1.Session.SendMessageResponse> observer = new CapturingObserver<>();

        service.sendMessage(messageRequest("user", "退款多久到账"), observer);

        assertEquals(1, publisher.events.size(), "用户消息应发布一条事件");
        PublishedEvent event = publisher.events.get(0);
        assertEquals(sessionId, event.sessionId());
        assertEquals("tenant-1", event.tenantId(), "租户需透传给 AI 域用于知识隔离");
        assertEquals("u1", event.userId(), "用户身份需透传给 AI 域用于跨会话长期记忆隔离");
        assertEquals(1L, event.seq(), "携带落库后的 seq");
        assertEquals("退款多久到账", event.content());
        assertEquals(observer.value.getMessageId(), event.messageId(), "事件消息 ID 与应答一致");
        // M3-3.11：首次触发 AI 后进入 AI_ACTIVE（客服队列可见、可接管）
        assertEquals(SessionStateMachine.State.AI_ACTIVE,
                repository.findById(sessionId).orElseThrow().getState());
    }

    @Test
    void secondTurnCarriesHistoryAndStaysAiActive() {
        service.sendMessage(messageRequest("user", "第一问"), new CapturingObserver<>());
        repository.findById(sessionId).orElseThrow()
                .appendMessage("agent", "ai", "第一个回答", "ai-1");

        CapturingObserver<lingxi.session.v1.Session.SendMessageResponse> observer = new CapturingObserver<>();
        service.sendMessage(messageRequest("user", "第二问"), observer);

        PublishedEvent second = publisher.events.get(publisher.events.size() - 1);
        assertEquals(SessionStateMachine.State.AI_ACTIVE,
                repository.findById(sessionId).orElseThrow().getState(), "AI 会话中保持 AI_ACTIVE");
        // 近 N 轮历史随事件透传（含上一轮用户问题与 AI 回答），且不含本条
        assertEquals(2, second.history().size());
        assertEquals("第一问", second.history().get(0).content());
        assertEquals("第一个回答", second.history().get(1).content());
        assertFalse(second.history().stream().anyMatch(m -> "第二问".equals(m.content())),
                "本条用户消息应通过 content 传递，不重复出现在 history");
    }

    @Test
    void takeoverStopsAiAnswers() {
        service.sendMessage(messageRequest("user", "先问一句"), new CapturingObserver<>());
        int publishedBeforeTakeover = publisher.events.size();
        service.takeOver(lingxi.session.v1.Session.TakeOverRequest.newBuilder()
                .setSessionId(sessionId).setAgentId("agent-9").build(), new CapturingObserver<>());

        service.sendMessage(messageRequest("user", "接管后我还想问"), new CapturingObserver<>());

        assertEquals(publishedBeforeTakeover, publisher.events.size(), "接管（HUMAN_CHAT）后用户消息不再触发 AI");
        assertEquals(SessionStateMachine.State.HUMAN_CHAT,
                repository.findById(sessionId).orElseThrow().getState());
        // M4-4.5：接管成功应发布 takeover 事件，通知 AI 域终止正在生成的回答
        assertEquals(1, publisher.takeovers.size(), "接管成功应记录一条 takeover 事件");
        TakeoverEvent takeover = publisher.takeovers.get(0);
        assertEquals(sessionId, takeover.sessionId());
        assertEquals("agent_takeover", takeover.reason());
        assertEquals("agent-9", takeover.agentId());
    }

    @Test
    void changeStateToHumanPublishesTakeoverWithStateChangeReason() {
        CapturingObserver<lingxi.session.v1.Session.ChangeStateResponse> observer = new CapturingObserver<>();
        service.changeState(lingxi.session.v1.Session.ChangeStateRequest.newBuilder()
                .setSessionId(sessionId)
                .setTargetState(lingxi.session.v1.Session.SessionState.HUMAN_CHAT)
                .setOperator("agent-7")
                .build(), observer);

        assertTrue(observer.value.getOk(), "合法迁移应成功");
        assertEquals(1, publisher.takeovers.size(), "迁移到 HUMAN_CHAT 应发布 takeover 事件");
        TakeoverEvent takeover = publisher.takeovers.get(0);
        assertEquals("state_change", takeover.reason());
        assertEquals("agent-7", takeover.agentId());
    }

    @Test
    void waitingHumanSessionAppearsInQueueAndSummaryIsPreviewed() {
        // M4-4.5：转人工会话仍在客服队列，且摘要（超长截断）随队列移交
        Session shortSummary = repository.findById(sessionId).orElseThrow();
        shortSummary.changeState(SessionStateMachine.State.WAITING_HUMAN);
        shortSummary.updateSummary("用户咨询退款时效");

        CapturingObserver<lingxi.session.v1.Session.CreateSessionResponse> created = new CapturingObserver<>();
        service.createSession(lingxi.session.v1.Session.CreateSessionRequest.newBuilder()
                .setTenantId("tenant-1").setUserId("u2").setChannel("web").build(), created);
        Session longSummary = repository.findById(created.value.getSessionId()).orElseThrow();
        longSummary.changeState(SessionStateMachine.State.WAITING_HUMAN);
        longSummary.updateSummary("长".repeat(260));

        CapturingObserver<lingxi.session.v1.Session.ListWaitingSessionsResponse> observer = new CapturingObserver<>();
        service.listWaitingSessions(lingxi.session.v1.Session.ListWaitingSessionsRequest.newBuilder()
                .setLimit(20).build(), observer);

        assertEquals(2, observer.value.getSessionsCount(), "两个 WAITING_HUMAN 会话都应出现在客服队列");
        Map<String, lingxi.session.v1.Session.SessionSummary> byId =
                observer.value.getSessionsList().stream()
                        .collect(Collectors.toMap(
                                lingxi.session.v1.Session.SessionSummary::getSessionId, s -> s));
        assertEquals(lingxi.session.v1.Session.SessionState.WAITING_HUMAN,
                byId.get(sessionId).getState(), "转人工会话状态透传为 WAITING_HUMAN");
        assertEquals("用户咨询退款时效", byId.get(sessionId).getSummary(), "简短摘要原样透传");
        assertEquals(200, byId.get(created.value.getSessionId()).getSummary().length(),
                "超长摘要应被截断到 summary-preview-len（200）");
    }

    @Test
    void summaryIsCarriedInEvent() {
        Session session = repository.findById(sessionId).orElseThrow();
        session.updateSummary("用户此前咨询过退款时效，已告知 3-7 个工作日");

        service.sendMessage(messageRequest("user", "那换货呢"), new CapturingObserver<>());

        assertEquals("用户此前咨询过退款时效，已告知 3-7 个工作日",
                publisher.events.get(0).summary(), "会话摘要随事件透传给 AI 域");
    }

    @Test
    void publishToleratesBlankUserId() {
        // 兜底：历史/匿名会话可能缺失 userId，发布端口需容忍空值，不得抛错
        assertDoesNotThrow(() -> publisher.publishUserMessage(sessionId, "tenant-1", null, "msg-x", 1L,
                "内容", "", List.of()));

        PublishedEvent event = publisher.events.get(0);
        assertNull(event.userId(), "userId 为空时仍能正常发布并如实记录");
    }

    @Test
    void agentMessageDoesNotPublish() {
        CapturingObserver<lingxi.session.v1.Session.SendMessageResponse> observer = new CapturingObserver<>();

        service.sendMessage(messageRequest("agent", "您好，我来帮您"), observer);

        assertTrue(publisher.events.isEmpty(), "坐席回复不应触发 AI 事件（避免循环应答）");
        assertNotNull(observer.value, "坐席消息仍应正常落库");
        assertEquals(SessionStateMachine.State.INIT,
                repository.findById(sessionId).orElseThrow().getState(), "坐席先开口不改变会话状态");
    }

    @Test
    void aiReplyAppendIsIdempotentById() {
        Session session = repository.findById(sessionId).orElseThrow();

        assertFalse(session.hasMessageId("ai-abc"), "初始不应存在该 AI 消息");
        session.appendMessage("agent", "ai", "回答内容", "ai-abc");

        assertTrue(session.hasMessageId("ai-abc"), "按消息 ID 可查到已落库的 AI 回答");
        assertEquals(1, session.messageCount(), "仅追加了一条 AI 回答消息");
    }

    private lingxi.session.v1.Session.SendMessageRequest messageRequest(String sender, String content) {
        return lingxi.session.v1.Session.SendMessageRequest.newBuilder()
                .setSessionId(sessionId).setSender(sender).setSenderId("u1").setContent(content).build();
    }
}
