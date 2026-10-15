package com.lingxi.session.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.session.model.Session;
import com.lingxi.session.repo.InMemorySessionRepository;
import com.lingxi.session.state.SessionStateMachine.State;

import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;

/**
 * 快捷回复协调器单测（M4-4.6）：纯离线，用替身端口/发布器覆盖状态校验、
 * 上下文组装、降级与入队落点，不触网、不连消息总线。
 */
class SuggestReplyCoordinatorTest {

    private static final int HISTORY_LIMIT = 20;

    /** 记录请求并可配置返回结果的同步端口替身。 */
    private static final class FakePort implements SuggestRepliesPort {
        private final List<GenerateSuggestRequest> requests = new ArrayList<>();
        private SuggestResult result = SuggestResult.ok(List.of("您好"), 12);

        @Override
        public SuggestResult ask(GenerateSuggestRequest request) {
            requests.add(request);
            return result;
        }
    }

    /** 记录请求并可配置入队成败的发布器替身。 */
    private static final class FakePublisher implements SuggestRequestPublisher {
        private final List<GenerateSuggestRequest> requests = new ArrayList<>();
        private boolean success = true;

        @Override
        public boolean enqueue(GenerateSuggestRequest request) {
            requests.add(request);
            return success;
        }
    }

    private InMemorySessionRepository repository;
    private FakePort port;
    private FakePublisher publisher;

    @BeforeEach
    void setUp() {
        repository = new InMemorySessionRepository();
        port = new FakePort();
        publisher = new FakePublisher();
    }

    @Test
    void normalReturnsSuggestions() {
        Session session = humanSession();
        port.result = SuggestResult.ok(List.of("建议A", "建议B"), 15);

        SuggestResult result = coordinator().suggest("req-1", session.getId(), "agent-1", "退款", 3);

        assertTrue(result.ok(), "正常应产出候选");
        assertFalse(result.degraded());
        assertFalse(result.queued(), "正常返回不应入队");
        assertEquals(List.of("建议A", "建议B"), result.suggestions());
        assertEquals(15, result.elapsedMs());
        assertTrue(publisher.requests.isEmpty(), "正常返回不应转异步队列");
    }

    @Test
    void degradedEnqueuesAndReportsQueued() {
        Session session = humanSession();
        port.result = SuggestResult.degraded("TIMEOUT", 4001);

        SuggestResult result = coordinator().suggest("req-2", session.getId(), "agent-1", "", 0);

        assertFalse(result.ok());
        assertTrue(result.degraded());
        assertEquals("TIMEOUT", result.degradeReason(), "降级原因透传");
        assertTrue(result.queued(), "降级且入队成功应回传 queued=true");
        assertEquals(1, publisher.requests.size(), "降级请求应转入异步队列");
    }

    @Test
    void enqueueFailureReportsUnavailable() {
        Session session = humanSession();
        port.result = SuggestResult.degraded("BUSY", 100);
        publisher.success = false;

        SuggestResult result = coordinator().suggest("req-3", session.getId(), "agent-1", "", 0);

        assertTrue(result.degraded());
        assertFalse(result.queued(), "入队失败不得谎报已排队");
        assertEquals("unavailable", result.degradeReason(), "入队失败应按不可用回传");
    }

    @Test
    void closedSessionRejected() {
        Session session = new Session("tenant-1", "u1", "web");
        repository.save(session);
        session.changeState(State.CLOSED);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> coordinator().suggest("req-4", session.getId(), "agent-1", "", 0));
        assertEquals("会话已关闭", error.getMessage());
        assertTrue(port.requests.isEmpty(), "已关闭会话不应发起同步调用");
    }

    @Test
    void nonHumanSessionRejected() {
        Session session = new Session("tenant-1", "u1", "web");  // INIT：AI 应答场景，非人工
        repository.save(session);

        assertThrows(IllegalStateException.class,
                () -> coordinator().suggest("req-5", session.getId(), "agent-1", "", 0));
        assertTrue(port.requests.isEmpty(), "非人工会话不应发起同步调用");
    }

    @Test
    void contextIsAssembledWithHistoryLimit() {
        Session session = waitingHumanSession();
        session.appendMessage("user", "u1", "问题1");
        session.appendMessage("agent", "ai", "回答1");
        session.appendMessage("user", "u1", "问题2");
        session.updateSummary("用户咨询退款");

        // history-limit=2：仅注入最近两条，且不含摘要
        SuggestResult result = new SuggestReplyCoordinator(repository, port, publisher, 2)
                .suggest("req-6", session.getId(), "agent-9", "要点X", 3);

        assertEquals(1, port.requests.size());
        GenerateSuggestRequest request = port.requests.get(0);
        assertEquals("req-6", request.getRequestId());
        assertEquals("tenant-1", request.getTenantId());
        assertEquals(session.getId(), request.getSessionId());
        assertEquals("agent-9", request.getAgentId());
        assertEquals("要点X", request.getIntent());
        assertEquals("用户咨询退款", request.getSummary(), "会话摘要应注入上下文");
        assertEquals(3, request.getMaxSuggestions());
        assertEquals(2, request.getHistoryCount(), "history-limit 应限制注入条数");
        assertEquals("agent", request.getHistory(0).getRole());
        assertEquals("回答1", request.getHistory(0).getContent());
        assertEquals("问题2", request.getHistory(1).getContent(), "注入的是最近一轮");
        assertEquals(2, result.historyCount(), "结果应回传实际注入条数");
    }

    @Test
    void unknownSessionRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> coordinator().suggest("req-7", "no-such-session", "agent-1", "", 0));
        assertTrue(error.getMessage().startsWith("会话不存在"), "缺失会话应报会话不存在");
    }

    private SuggestReplyCoordinator coordinator() {
        return new SuggestReplyCoordinator(repository, port, publisher, HISTORY_LIMIT);
    }

    /** 人工会话（INIT → HUMAN_CHAT）。 */
    private Session humanSession() {
        Session session = new Session("tenant-1", "u1", "web");
        repository.save(session);
        session.takeOver("agent-1");
        return session;
    }

    /** 待人工接入会话（INIT → WAITING_HUMAN），快捷回复同样适用。 */
    private Session waitingHumanSession() {
        Session session = new Session("tenant-1", "u1", "web");
        repository.save(session);
        session.changeState(State.WAITING_HUMAN);
        return session;
    }
}
