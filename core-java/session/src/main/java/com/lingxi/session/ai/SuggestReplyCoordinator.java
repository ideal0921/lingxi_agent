package com.lingxi.session.ai;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.session.state.SessionStateMachine.State;

import lingxi.agentsync.v1.AgentSync.ChatTurn;
import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;

/**
 * 坐席快捷回复协调器（M4-4.6）：纯业务逻辑，不依赖 gRPC/Kafka，便于离线单测。
 *
 * <p>职责：校验会话状态 → 组装上下文（摘要 + 近 N 轮）→ 同步调 AI 生成候选；
 * 同步通道降级时把请求转入异步队列，并如实回传 {@code queued}。</p>
 */
@Component
public class SuggestReplyCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SuggestReplyCoordinator.class);

    private final SessionRepository repository;
    private final SuggestRepliesPort port;
    private final SuggestRequestPublisher publisher;
    /** 注入的「近 N 轮」上下文条数上限。 */
    private final int historyLimit;

    public SuggestReplyCoordinator(SessionRepository repository, SuggestRepliesPort port,
                                   SuggestRequestPublisher publisher,
                                   @Value("${lingxi.suggest.history-limit:20}") int historyLimit) {
        this.repository = repository;
        this.port = port;
        this.publisher = publisher;
        this.historyLimit = historyLimit;
    }

    /**
     * 生成回复建议草稿。
     *
     * @param requestId      三端贯穿的请求标识
     * @param sessionId      会话 ID
     * @param agentId        发起坐席 ID
     * @param intent         坐席意图/要点（可选）
     * @param maxSuggestions 候选条数上限（0 表示用服务端默认）
     * @return 生成结果（含降级与入队标记）
     * @throws IllegalArgumentException 会话不存在（gRPC 层映射 {@code NOT_FOUND}）
     * @throws IllegalStateException    会话已关闭或非人工会话（映射 {@code FAILED_PRECONDITION}）
     */
    public SuggestResult suggest(String requestId, String sessionId, String agentId,
                                 String intent, int maxSuggestions) {
        Session session = repository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        State state = session.getState();
        if (state == State.CLOSED) {
            throw new IllegalStateException("会话已关闭");
        }
        // 快捷回复属人工场景：AI 应答中/待接入的会话不提供坐席快捷回复
        if (state != State.HUMAN_CHAT && state != State.WAITING_HUMAN) {
            throw new IllegalStateException("会话状态不支持快捷回复: " + state);
        }

        List<SessionMessage> history = session.recentMessages(historyLimit);
        GenerateSuggestRequest request = buildRequest(requestId, session, agentId, intent, maxSuggestions, history);

        SuggestResult result = port.ask(request);
        if (!result.degraded()) {
            return result.withHistoryCount(history.size());
        }
        // 降级落点在 Java：转异步队列；入队失败则如实回传 unavailable，让前端提示重试
        if (publisher.enqueue(request)) {
            log.info("同步建议降级转异步队列 sessionId={} reason={} requestId={}",
                    sessionId, result.degradeReason(), requestId);
            return result.withQueued(true).withHistoryCount(history.size());
        }
        log.warn("同步建议降级且入队失败 sessionId={} reason={} requestId={}",
                sessionId, result.degradeReason(), requestId);
        return SuggestResult.degraded("unavailable", result.elapsedMs()).withHistoryCount(history.size());
    }

    /** 组装 AI 域生成请求：长程摘要 + 近 N 轮（仅 role/content）。 */
    private GenerateSuggestRequest buildRequest(String requestId, Session session, String agentId,
                                                String intent, int maxSuggestions, List<SessionMessage> history) {
        GenerateSuggestRequest.Builder builder = GenerateSuggestRequest.newBuilder()
                .setRequestId(nullToEmpty(requestId))
                .setTenantId(nullToEmpty(session.getTenantId()))
                .setSessionId(session.getId())
                .setAgentId(nullToEmpty(agentId))
                .setIntent(nullToEmpty(intent))
                .setSummary(nullToEmpty(session.getSummary()))
                .setMaxSuggestions(maxSuggestions);
        for (SessionMessage message : history) {
            builder.addHistory(ChatTurn.newBuilder()
                    .setRole(nullToEmpty(message.sender()))
                    .setContent(nullToEmpty(message.content()))
                    .build());
        }
        return builder.build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
