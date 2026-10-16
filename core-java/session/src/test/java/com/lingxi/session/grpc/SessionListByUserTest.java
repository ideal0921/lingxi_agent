package com.lingxi.session.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;
import com.lingxi.common.tenant.TenantIsolationAuditor;
import com.lingxi.session.ai.NoOpSuggestRepliesPort;
import com.lingxi.session.ai.NoOpSuggestRequestPublisher;
import com.lingxi.session.ai.SuggestReplyCoordinator;
import com.lingxi.session.event.NoOpAnalyticsEventPublisher;
import com.lingxi.session.event.NoOpSessionEventPublisher;
import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.model.SessionOverview;
import com.lingxi.session.repo.InMemorySessionRepository;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.session.state.SessionStateMachine.State;

import io.grpc.stub.StreamObserver;

/**
 * 历史会话列表（M5-5.7）单测：验证「列我的会话」按可信身份过滤、最近活跃倒序、
 * 预览截断与条数上限；并守住「所有会话变更都经 SessionRepository 端口落库」，
 * 否则切 MySQL 后内存态消息会丢失。
 *
 * <p>说明：本用例用 InMemory 仓储 + 记录型端口替身，不依赖 MySQL（与既有测试风格一致）。</p>
 */
class SessionListByUserTest {

    private static final String TENANT = "tenant-1";
    private static final String USER = "u1";
    /** 与 application.yml 的 lingxi.session.list-preview-len 默认值一致。 */
    private static final int PREVIEW_LEN = 60;

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

    /** 委托 InMemory 的仓储替身：记录 save 调用次数，用于断言「变更经端口落库」。 */
    private static final class RecordingSessionRepository implements SessionRepository {
        private final InMemorySessionRepository delegate = new InMemorySessionRepository();
        private final AtomicInteger saveCount = new AtomicInteger();

        @Override
        public Session save(Session session) {
            saveCount.incrementAndGet();
            return delegate.save(session);
        }

        @Override
        public int appendMessage(String sessionId, SessionMessage draft) {
            return delegate.appendMessage(sessionId, draft);
        }

        @Override
        public Optional<Session> findById(String sessionId) {
            return delegate.findById(sessionId);
        }

        @Override
        public Optional<Session> findById(String sessionId, String tenantId) {
            return delegate.findById(sessionId, tenantId);
        }

        @Override
        public List<Session> listByStates(Set<State> states, String tenantId) {
            return delegate.listByStates(states, tenantId);
        }

        @Override
        public List<Session> listByAgent(String agentId, String tenantId) {
            return delegate.listByAgent(agentId, tenantId);
        }

        @Override
        public List<SessionOverview> listByUser(String userId, String tenantId, int limit) {
            return delegate.listByUser(userId, tenantId, limit);
        }
    }

    private RecordingSessionRepository repository;
    private SessionServiceGrpcImpl service;

    @BeforeEach
    void setUp() {
        repository = new RecordingSessionRepository();
        TenantContext.set(new TenantIdentity(TENANT, USER, "user"));
        service = new SessionServiceGrpcImpl(repository, new NoOpSessionEventPublisher(), 20, 200, PREVIEW_LEN,
                new SuggestReplyCoordinator(repository, new NoOpSuggestRepliesPort(),
                        new NoOpSuggestRequestPublisher(), 20),
                new NoOpAnalyticsEventPublisher(), noOpAuditor());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listsOwnSessionsOrderedByLastActiveDescWithPreviewAndCount() throws InterruptedException {
        // 旧会话：先建并已有两条消息（末条为 agent 长消息）
        String oldSessionId = createSession(USER);
        sendMessage(oldSessionId, "user", "我想查询退款进度");
        String longContent = "很长的消息内容".repeat(20);
        sendMessage(oldSessionId, "agent", longContent);
        Thread.sleep(5);
        // 新会话：后建且无消息（列表应排在前面，预览为空、条数为 0）
        String newSessionId = createSession(USER);

        CapturingObserver<lingxi.session.v1.Session.ListSessionsByUserResponse> observer = list(0);

        assertNull(observer.error, "列会话不应报错");
        List<lingxi.session.v1.Session.SessionListItem> items = observer.value.getSessionsList();
        assertEquals(2, items.size(), "只返回本人会话");

        var newest = items.get(0);
        assertEquals(newSessionId, newest.getSessionId(), "最近活跃的会话排在前");
        assertEquals(USER, newest.getUserId());
        assertEquals("web", newest.getChannel());
        assertEquals(lingxi.session.v1.Session.SessionState.INIT, newest.getState());
        assertEquals(0, newest.getMessageCount());
        assertEquals("", newest.getLastPreview(), "无消息时预览为空");
        assertTrue(newest.getCreatedAtMs() > 0 && newest.getLastActiveAtMs() > 0, "时间戳应透传");

        var older = items.get(1);
        assertEquals(oldSessionId, older.getSessionId());
        assertEquals(2, older.getMessageCount(), "条数取自消息序列");
        assertEquals(lingxi.session.v1.Session.SessionState.AI_ACTIVE, older.getState());
        assertEquals(PREVIEW_LEN, older.getLastPreview().length(), "预览按展示长度截断");
        assertEquals(longContent.substring(0, PREVIEW_LEN), older.getLastPreview());
    }

    @Test
    void excludesOtherTenantAndOtherUserSessions() {
        createSession(USER);            // 本租户本人
        // 归属以**可信主体**为准（请求体 user_id 仅兼容字段），故「另一个用户的会话」必须切身份来造，
        // 不能靠传不同 user_id——后者会被忽略（见 ignoresRequestedUserIdAndUsesTrustedSubject）
        TenantContext.set(new TenantIdentity(TENANT, "u2", "user"));
        createSession("u2");
        TenantContext.set(new TenantIdentity(TENANT, USER, "user"));
        assertEquals(1, list(0).value.getSessionsList().size(), "不得带出同租户其他用户的会话");

        // 切到其他租户：本人会话对外不可见（按身份租户强制过滤）
        TenantContext.set(new TenantIdentity("tenant-2", USER, "user"));
        assertEquals(0, list(0).value.getSessionsList().size(), "不得跨租户带出会话");
    }

    /**
     * 建会话的归属用户一律取**可信主体**，请求体 user_id 仅兼容字段被忽略。
     * 否则可把会话挂到他人名下（越权），且「按本人主体查历史会话」会因用户键不同源而查不到。
     */
    @Test
    void ignoresRequestedUserIdAndUsesTrustedSubject() {
        String sid = createSession("someone-else");

        CapturingObserver<lingxi.session.v1.Session.ListSessionsByUserResponse> observer = list(0);
        assertNull(observer.error);
        assertEquals(1, observer.value.getSessionsList().size(), "按可信主体应能查到自己刚建的会话");
        var item = observer.value.getSessionsList().get(0);
        assertEquals(sid, item.getSessionId());
        assertEquals(USER, item.getUserId(), "归属应为可信主体，而非请求体传入的 user_id");
    }

    @Test
    void returnsEmptyWhenUserIdentityMissing() {
        createSession(USER);
        // 令牌无用户主体（匿名接入）：返回空列表而非报错
        TenantContext.set(new TenantIdentity(TENANT, "", "user"));
        CapturingObserver<lingxi.session.v1.Session.ListSessionsByUserResponse> observer = list(0);
        assertNull(observer.error);
        assertTrue(observer.value.getSessionsList().isEmpty());
    }

    @Test
    void respectsLimitAndCapsAtServerMaximum() throws InterruptedException {
        createSession(USER);
        Thread.sleep(5);
        createSession(USER);
        Thread.sleep(5);
        String latest = createSession(USER);

        assertEquals(3, list(0).value.getSessionsList().size(), "limit<=0 时用服务端默认（此处不足默认值）");
        assertEquals(2, list(2).value.getSessionsList().size(), "limit 生效");
        assertEquals(latest, list(2).value.getSessionsList().get(0).getSessionId(), "取最近活跃的前 N 条");
        // 超过上限时服务端收敛为 50：此处仅 3 个会话，仍全部返回（不报错即可）
        assertEquals(3, list(999).value.getSessionsList().size());
    }

    @Test
    void allSessionMutationsGoThroughRepositoryPort() {
        // 建会话落库
        String sessionId = createSession(USER);
        int afterCreate = repository.saveCount.get();
        assertTrue(afterCreate >= 1, "建会话应经端口落库");

        // 首条用户消息（INIT → AI_ACTIVE）落库
        sendMessage(sessionId, "user", "你好");
        int afterFirst = repository.saveCount.get();
        assertTrue(afterFirst > afterCreate, "用户消息应经端口落库");

        // **AI_ACTIVE 下的后续消息同样落库**（回归点：旧实现只在 INIT→AI_ACTIVE 时 save，
        // 切 MySQL 后此类消息会丢失）
        sendMessage(sessionId, "user", "第二条消息");
        assertTrue(repository.saveCount.get() > afterFirst, "AI_ACTIVE 会话的后续消息也应落库");

        // 状态迁移落库
        int beforeChange = repository.saveCount.get();
        service.changeState(lingxi.session.v1.Session.ChangeStateRequest.newBuilder()
                .setSessionId(sessionId).setTargetState(lingxi.session.v1.Session.SessionState.WAITING_HUMAN)
                .build(), new CapturingObserver<>());
        assertTrue(repository.saveCount.get() > beforeChange, "状态迁移应经端口落库");

        // 接管落库
        int beforeTakeOver = repository.saveCount.get();
        CapturingObserver<lingxi.session.v1.Session.TakeOverResponse> takeOver = new CapturingObserver<>();
        service.takeOver(lingxi.session.v1.Session.TakeOverRequest.newBuilder()
                .setSessionId(sessionId).setAgentId("agent-1").build(), takeOver);
        assertTrue(takeOver.value.getOk(), "WAITING_HUMAN 会话应可被接管");
        assertTrue(repository.saveCount.get() > beforeTakeOver, "接管应经端口落库");
        assertEquals(State.HUMAN_CHAT, repository.findById(sessionId).orElseThrow().getState());
        assertEquals("agent-1", repository.findById(sessionId).orElseThrow().getAgentId());
    }

    /** 建会话（本人）并返回会话 ID。 */
    private String createSession(String userId) {
        CapturingObserver<lingxi.session.v1.Session.CreateSessionResponse> observer = new CapturingObserver<>();
        service.createSession(lingxi.session.v1.Session.CreateSessionRequest.newBuilder()
                .setTenantId(TENANT).setUserId(userId).setChannel("web").build(), observer);
        return observer.value.getSessionId();
    }

    private void sendMessage(String sessionId, String sender, String content) {
        service.sendMessage(lingxi.session.v1.Session.SendMessageRequest.newBuilder()
                .setSessionId(sessionId).setSender(sender).setSenderId(sender.equals("user") ? USER : "ai")
                .setContent(content).build(), new CapturingObserver<>());
    }

    private CapturingObserver<lingxi.session.v1.Session.ListSessionsByUserResponse> list(int limit) {
        CapturingObserver<lingxi.session.v1.Session.ListSessionsByUserResponse> observer = new CapturingObserver<>();
        service.listSessionsByUser(lingxi.session.v1.Session.ListSessionsByUserRequest.newBuilder()
                .setLimit(limit).build(), observer);
        return observer;
    }

    private static TenantIsolationAuditor noOpAuditor() {
        return violation -> {
            // 审计空实现：本用例不校验越权事实
        };
    }
}
