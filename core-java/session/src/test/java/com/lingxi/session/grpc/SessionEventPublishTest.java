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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.common.tenant.IsolationViolation;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;
import com.lingxi.common.tenant.TenantIsolationAuditor;
import com.lingxi.session.ai.NoOpSuggestRepliesPort;
import com.lingxi.session.ai.NoOpSuggestRequestPublisher;
import com.lingxi.session.ai.SuggestReplyCoordinator;
import com.lingxi.session.event.AnalyticsEventPublisher;
import com.lingxi.session.event.SessionEventPublisher;
import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.repo.InMemorySessionRepository;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.session.state.SessionStateMachine;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * 会话域事件发布与上下文窗口单测（M3-3.8 / M3-3.11）：
 * 验证用户消息触发事件、坐席回复不触发、AI 回答按消息 ID 幂等落库，
 * 以及 AI_ACTIVE 状态迁移、近 N 轮历史与摘要随事件透传、接管后 AI 退出。
 *
 * <p>M5-5.5：新增租户隔离用例——跨租户操作被拒且落隔离审计、队列按租户过滤、伪造租户被拒。</p>
 */
class SessionEventPublishTest {

    /** 会话归属租户（与测试上下文身份一致）。 */
    private static final String TENANT = "tenant-1";

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
    private RecordingAnalyticsPublisher analytics;
    private RecordingIsolationAuditor auditor;
    private SessionRepository repository;
    private SessionServiceGrpcImpl service;
    private String sessionId;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        analytics = new RecordingAnalyticsPublisher();
        auditor = new RecordingIsolationAuditor();
        repository = new InMemorySessionRepository();
        // M5-5.5：会话域要求可信租户上下文（生产由 gRPC 拦截器注入）
        TenantContext.set(new TenantIdentity(TENANT, "u1", "user"));
        // M4-4.6：快捷回复走离线空实现（本用例不校验快捷回复链路）
        // M5-5.1：分析埋点用记录型空实现（便于断言生命周期事件）
        // M5-5.5：隔离审计用记录型替身（便于断言越权事实）
        service = new SessionServiceGrpcImpl(repository, publisher, 20, 200, 60,
                new SuggestReplyCoordinator(repository, new NoOpSuggestRepliesPort(),
                        new NoOpSuggestRequestPublisher(), 20),
                analytics, auditor);

        CapturingObserver<lingxi.session.v1.Session.CreateSessionResponse> observer = new CapturingObserver<>();
        service.createSession(
                lingxi.session.v1.Session.CreateSessionRequest.newBuilder()
                        .setTenantId(TENANT).setUserId("u1").setChannel("web").build(),
                observer);
        sessionId = observer.value.getSessionId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
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

    // ---------- M5-5.1 分析埋点 ----------

    @Test
    void lifecycleStagesArePublishedForCreateMessageTakeoverAndClose() {
        // 建会话即已发布 created（setUp 内建过一次会话）
        assertEquals(List.of("created"), analytics.stages(), "建会话应发布 created 埋点");
        assertEquals("tenant-1", analytics.lifecycle.get(0).tenantId(), "埋点需自带租户（会话域为内存实现，不可回溯）");
        assertEquals("web", analytics.lifecycle.get(0).channel(), "埋点需自带渠道");

        service.sendMessage(messageRequest("user", "退款多久到账"), new CapturingObserver<>());
        service.takeOver(lingxi.session.v1.Session.TakeOverRequest.newBuilder()
                .setSessionId(sessionId).setAgentId("agent-9").build(), new CapturingObserver<>());
        service.changeState(lingxi.session.v1.Session.ChangeStateRequest.newBuilder()
                .setSessionId(sessionId)
                .setTargetState(lingxi.session.v1.Session.SessionState.CLOSED)
                .build(), new CapturingObserver<>());

        assertEquals(List.of("created", "user_msg", "handoff", "closed"), analytics.stages(),
                "用户消息 / 接管 / 关闭 各自发布对应阶段埋点");
        assertEquals("agent-9", analytics.lifecycle.get(2).agentId(), "handoff 埋点应携带坐席");
    }

    @Test
    void stateChangeToHumanAlsoPublishesHandoff() {
        service.changeState(lingxi.session.v1.Session.ChangeStateRequest.newBuilder()
                .setSessionId(sessionId)
                .setTargetState(lingxi.session.v1.Session.SessionState.HUMAN_CHAT)
                .setOperator("agent-7")
                .build(), new CapturingObserver<>());

        assertEquals(List.of("created", "handoff"), analytics.stages(), "状态迁至人工同样计入 handoff 阶段");
    }

    @Test
    void submitFeedbackValidatesAndPublishes() {
        // 会话不存在 → 拒绝
        CapturingObserver<lingxi.session.v1.Session.SubmitFeedbackResponse> missing = new CapturingObserver<>();
        service.submitFeedback(lingxi.session.v1.Session.SubmitFeedbackRequest.newBuilder()
                .setSessionId("no-such-session").setRating(5).build(), missing);
        assertFalse(missing.value.getOk(), "会话不存在应拒绝");

        // 评分越界 → 拒绝
        CapturingObserver<lingxi.session.v1.Session.SubmitFeedbackResponse> badRating = new CapturingObserver<>();
        service.submitFeedback(lingxi.session.v1.Session.SubmitFeedbackRequest.newBuilder()
                .setSessionId(sessionId).setRating(9).build(), badRating);
        assertFalse(badRating.value.getOk(), "评分越界应拒绝");
        assertTrue(analytics.feedback.isEmpty(), "被拒的评价不得产生埋点");

        // 合法评价 → 通过，且租户/用户由服务端按会话解析
        CapturingObserver<lingxi.session.v1.Session.SubmitFeedbackResponse> ok = new CapturingObserver<>();
        service.submitFeedback(lingxi.session.v1.Session.SubmitFeedbackRequest.newBuilder()
                .setSessionId(sessionId).setRating(5).setComment("  解答很清楚  ").build(), ok);
        assertTrue(ok.value.getOk(), "合法评价应通过");
        assertEquals(1, analytics.feedback.size(), "应发布一条满意度埋点");
        assertEquals("tenant-1", analytics.feedback.get(0).tenantId(), "租户由会话解析（前端不传）");
        assertEquals("u1", analytics.feedback.get(0).userId(), "用户由会话解析");
        assertEquals("解答很清楚", analytics.feedback.get(0).comment(), "评价内容应去除首尾空白");
    }

    // ---------- M5-5.5 租户隔离加固 ----------

    @Test
    void createSessionRejectsForgedTenantAndAudits() {
        // 请求体租户与可信身份租户不一致 → 拒绝（防伪造）并落审计
        CapturingObserver<lingxi.session.v1.Session.CreateSessionResponse> observer = new CapturingObserver<>();
        service.createSession(lingxi.session.v1.Session.CreateSessionRequest.newBuilder()
                .setTenantId("tenant-other").setUserId("u9").setChannel("web").build(), observer);

        assertNull(observer.value, "伪造租户不得创建会话");
        assertEquals(Status.PERMISSION_DENIED.getCode(), statusCodeOf(observer.error));
        assertEquals(1, auditor.violations.size(), "伪造租户应落一条隔离审计");
        IsolationViolation violation = auditor.violations.get(0);
        assertEquals("create_session", violation.action(), "审计动作应为建会话");
        assertEquals("tenant-other", violation.resourceTenantId(), "审计应记录被伪造的租户");
    }

    @Test
    void createSessionAcceptsMatchingTenantField() {
        // 请求体租户与身份一致（兼容字段）时正常创建，且归属可信租户
        CapturingObserver<lingxi.session.v1.Session.CreateSessionResponse> observer = new CapturingObserver<>();
        service.createSession(lingxi.session.v1.Session.CreateSessionRequest.newBuilder()
                .setTenantId(TENANT).setUserId("u9").setChannel("web").build(), observer);

        assertNotNull(observer.value, "租户一致应正常建会话");
        assertEquals(TENANT, repository.findById(observer.value.getSessionId()).orElseThrow().getTenantId(),
                "会话归属取可信租户");
        assertTrue(auditor.violations.isEmpty(), "一致场景不应产生审计");
    }

    @Test
    void crossTenantSessionOperationsAreDeniedAndAudited() {
        // 身份切到另一租户，对 tenant-1 的会话发起各类操作
        TenantContext.set(new TenantIdentity("tenant-2", "u2", "user"));

        CapturingObserver<lingxi.session.v1.Session.SendMessageResponse> send = new CapturingObserver<>();
        service.sendMessage(messageRequest("user", "越权提问"), send);
        CapturingObserver<lingxi.session.v1.Session.TakeOverResponse> takeOver = new CapturingObserver<>();
        service.takeOver(lingxi.session.v1.Session.TakeOverRequest.newBuilder()
                .setSessionId(sessionId).setAgentId("agent-2").build(), takeOver);
        CapturingObserver<lingxi.session.v1.Session.PullMessagesResponse> pull = new CapturingObserver<>();
        service.pullMessages(lingxi.session.v1.Session.PullMessagesRequest.newBuilder()
                .setSessionId(sessionId).build(), pull);

        // 对外统一按「不存在」返回（不泄露存在性），避免枚举他人资源
        assertEquals(Status.NOT_FOUND.getCode(), statusCodeOf(send.error));
        assertEquals(Status.NOT_FOUND.getCode(), statusCodeOf(takeOver.error));
        assertEquals(Status.NOT_FOUND.getCode(), statusCodeOf(pull.error));
        assertNull(takeOver.value, "越权不得成功接管");

        assertEquals(3, auditor.violations.size(), "三次越权各落一条审计");
        assertEquals(List.of("send_message", "take_over", "pull_messages"),
                auditor.violations.stream().map(IsolationViolation::action).toList(),
                "审计动作应与实际调用对应");
        assertTrue(publisher.events.isEmpty(), "越权不得触发 AI 应答事件");
        assertEquals(SessionStateMachine.State.INIT,
                repository.findById(sessionId).orElseThrow().getState(), "会话状态不得被越权修改");
    }

    @Test
    void waitingQueueIsFilteredByCallerTenant() {
        // 另一租户的会话进入队列
        TenantContext.set(new TenantIdentity("tenant-2", "u2", "user"));
        CapturingObserver<lingxi.session.v1.Session.CreateSessionResponse> created = new CapturingObserver<>();
        service.createSession(lingxi.session.v1.Session.CreateSessionRequest.newBuilder()
                .setUserId("u2").setChannel("web").build(), created);
        repository.findById(created.value.getSessionId()).orElseThrow()
                .changeState(SessionStateMachine.State.WAITING_HUMAN);

        // 回到 tenant-1 视角拉队列：只应看到本租户会话
        TenantContext.set(new TenantIdentity(TENANT, "u1", "user"));
        CapturingObserver<lingxi.session.v1.Session.ListWaitingSessionsResponse> observer = new CapturingObserver<>();
        service.listWaitingSessions(lingxi.session.v1.Session.ListWaitingSessionsRequest.newBuilder()
                .setLimit(50).build(), observer);

        assertEquals(1, observer.value.getSessionsCount(), "队列应只含调用方租户会话");
        assertEquals(sessionId, observer.value.getSessions(0).getSessionId());

        // 坐席会话列表同样按租户过滤
        repository.findById(sessionId).orElseThrow().takeOver("agent-1");
        CapturingObserver<lingxi.session.v1.Session.ListAgentSessionsResponse> agentView = new CapturingObserver<>();
        service.listAgentSessions(lingxi.session.v1.Session.ListAgentSessionsRequest.newBuilder()
                .setAgentId("agent-1").build(), agentView);
        assertEquals(1, agentView.value.getSessionsCount(), "坐席会话列表应按租户过滤");
        assertEquals(sessionId, agentView.value.getSessions(0).getSessionId());
    }

    @Test
    void crossTenantFeedbackIsRejectedAndAudited() {
        TenantContext.set(new TenantIdentity("tenant-2", "u2", "user"));

        CapturingObserver<lingxi.session.v1.Session.SubmitFeedbackResponse> observer = new CapturingObserver<>();
        service.submitFeedback(lingxi.session.v1.Session.SubmitFeedbackRequest.newBuilder()
                .setSessionId(sessionId).setRating(5).build(), observer);

        assertFalse(observer.value.getOk(), "跨租户评价应被拒绝");
        assertEquals(1, auditor.violations.size(), "跨租户评价应落审计");
        assertTrue(analytics.feedback.isEmpty(), "被拒评价不得产生埋点");
    }

    @Test
    void missingTenantContextIsUnauthenticated() {
        TenantContext.clear();

        CapturingObserver<lingxi.session.v1.Session.SendMessageResponse> observer = new CapturingObserver<>();
        service.sendMessage(messageRequest("user", "无租户上下文"), observer);

        assertEquals(Status.UNAUTHENTICATED.getCode(), statusCodeOf(observer.error),
                "缺少租户上下文应回未认证");
    }

    /** gRPC 错误码提取（无错误时返回 UNKNOWN，便于断言失败时给出可读结果）。 */
    private static Status.Code statusCodeOf(Throwable error) {
        if (error instanceof StatusRuntimeException runtimeException) {
            return runtimeException.getStatus().getCode();
        }
        return Status.Code.UNKNOWN;
    }

    private lingxi.session.v1.Session.SendMessageRequest messageRequest(String sender, String content) {
        return lingxi.session.v1.Session.SendMessageRequest.newBuilder()
                .setSessionId(sessionId).setSender(sender).setSenderId("u1").setContent(content).build();
    }

    /** 记录型分析埋点替身（M5-5.1）：便于断言各阶段是否按预期发布。 */
    private static final class RecordingAnalyticsPublisher implements AnalyticsEventPublisher {

        private final List<Lifecycle> lifecycle = new ArrayList<>();
        private final List<Feedback> feedback = new ArrayList<>();

        @Override
        public void publishLifecycle(String sessionId, String tenantId, String stage, String channel,
                                     String userId, String agentId, String messageId) {
            lifecycle.add(new Lifecycle(sessionId, tenantId, stage, channel, userId, agentId, messageId));
        }

        @Override
        public void publishFeedback(String sessionId, String tenantId, String userId, int rating,
                                   String comment, String tags) {
            feedback.add(new Feedback(sessionId, tenantId, userId, rating, comment, tags));
        }

        /** 已发布的阶段序列（按发布顺序）。 */
        private List<String> stages() {
            return lifecycle.stream().map(Lifecycle::stage).toList();
        }

        private record Lifecycle(String sessionId, String tenantId, String stage, String channel,
                                 String userId, String agentId, String messageId) {
        }

        private record Feedback(String sessionId, String tenantId, String userId, int rating,
                                 String comment, String tags) {
        }
    }

    /** 记录型隔离审计替身（M5-5.5）：便于断言越权事实是否上报。 */
    private static final class RecordingIsolationAuditor implements TenantIsolationAuditor {

        private final List<IsolationViolation> violations = new ArrayList<>();

        @Override
        public void record(IsolationViolation violation) {
            violations.add(violation);
        }
    }
}
