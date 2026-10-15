package com.lingxi.session.grpc;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.lingxi.session.ai.SuggestReplyCoordinator;
import com.lingxi.session.ai.SuggestResult;
import com.lingxi.session.event.AnalyticsEventPublisher;
import com.lingxi.session.event.SessionEventPublisher;
import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.session.state.IllegalStateTransitionException;
import com.lingxi.session.state.SessionStateMachine;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lingxi.session.v1.SessionServiceGrpc;

/**
 * 会话域 gRPC 服务实现：网关通过此接口完成建会话、发消息、状态迁移与断连续传。
 * 非法迁移与重复关闭被拒绝并返回原因。
 */
@Component
public class SessionServiceGrpcImpl extends SessionServiceGrpc.SessionServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(SessionServiceGrpcImpl.class);

    /** 消息内容长度上限（字符），超出拒绝写入。 */
    private static final int MAX_CONTENT_LEN = 4000;
    /** 满意度评分上限（1~5 星，越界拒绝）。 */
    private static final int MAX_RATING = 5;
    /** 评价内容长度上限（字符），超出截断。 */
    private static final int MAX_COMMENT_LEN = 500;
    /** 评价标签长度上限（字符），超出截断。 */
    private static final int MAX_TAGS_LEN = 255;

    private final SessionRepository repository;
    private final SessionEventPublisher eventPublisher;
    /** 随事件带给 AI 域的「近 N 轮」消息条数上限（M3-3.11 上下文窗口）。 */
    private final int historyLimit;
    /** 客服队列下发摘要预览的截断长度（M4-4.5 摘要移交），避免列表 payload 膨胀。 */
    private final int summaryPreviewLen;
    /** 坐席快捷回复协调器（M4-4.6）：组装上下文并同步调 AI 生成候选。 */
    private final SuggestReplyCoordinator suggestCoordinator;
    /** 分析埋点发布端口（M5-5.1）：会话生命周期与满意度事件。 */
    private final AnalyticsEventPublisher analyticsPublisher;

    public SessionServiceGrpcImpl(SessionRepository repository, SessionEventPublisher eventPublisher,
                                  @Value("${lingxi.session.history-limit:20}") int historyLimit,
                                  @Value("${lingxi.session.summary-preview-len:200}") int summaryPreviewLen,
                                  SuggestReplyCoordinator suggestCoordinator,
                                  AnalyticsEventPublisher analyticsPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.historyLimit = historyLimit;
        this.summaryPreviewLen = summaryPreviewLen;
        this.suggestCoordinator = suggestCoordinator;
        this.analyticsPublisher = analyticsPublisher;
    }

    @Override
    public void createSession(lingxi.session.v1.Session.CreateSessionRequest request,
                              StreamObserver<lingxi.session.v1.Session.CreateSessionResponse> observer) {
        try {
            validateCreateRequest(request);
            Session session = new Session(request.getTenantId(), request.getUserId(), request.getChannel());
            repository.save(session);
            log.info("创建会话 sessionId={} userId={} channel={}", session.getId(), request.getUserId(), request.getChannel());
            // M5-5.1 分析埋点：建会话（漏斗分母）
            analyticsPublisher.publishLifecycle(session.getId(), session.getTenantId(),
                    AnalyticsEventPublisher.STAGE_CREATED, session.getChannel(),
                    session.getUserId(), "", "");

            observer.onNext(lingxi.session.v1.Session.CreateSessionResponse.newBuilder()
                    .setSessionId(session.getId())
                    .setState(toProtoState(session.getState()))
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void sendMessage(lingxi.session.v1.Session.SendMessageRequest request,
                            StreamObserver<lingxi.session.v1.Session.SendMessageResponse> observer) {
        Session session = requireSession(request.getSessionId(), observer);
        if (session == null) {
            return;
        }
        if (session.getState() == SessionStateMachine.State.CLOSED) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("会话已关闭，禁止发送消息").asRuntimeException());
            return;
        }
        String content = request.getContent() == null ? "" : request.getContent();
        if (content.length() > MAX_CONTENT_LEN) {
            observer.onError(Status.INVALID_ARGUMENT
                    .withDescription("消息长度超过上限 " + MAX_CONTENT_LEN).asRuntimeException());
            return;
        }
        // 上下文窗口（M3-3.11）：先快照「本条之前」的近 N 轮消息，随事件带给 AI 域
        List<SessionMessage> history = session.recentMessages(historyLimit);
        SessionMessage saved = session.appendMessage(request.getSender(), request.getSenderId(), content);
        log.info("会话 {} 收到消息 seq={} sender={} 历史条数={} 摘要长度={}", session.getId(),
                saved.seq(), request.getSender(), history.size(), session.getSummary().length());

        // 用户消息落库后广播事件，触发 AI 流式应答（M3-3.8 异步主路径）。
        // M3-3.11：仅在可通过 AI 应答的状态（INIT / AI_ACTIVE）下触发；座席已接管
        // （HUMAN_CHAT）后 AI 退出，不再应答；坐席回复不触发（避免循环应答）。
        if ("user".equalsIgnoreCase(request.getSender()) && aiAnswerable(session)) {
            if (session.getState() == SessionStateMachine.State.INIT) {
                session.changeState(SessionStateMachine.State.AI_ACTIVE);  // 用户消息触发 AI：进入 AI 应答中
                repository.save(session);
                log.info("会话 {} 进入 AI_ACTIVE", session.getId());
            }
            eventPublisher.publishUserMessage(session.getId(), session.getTenantId(), session.getUserId(),
                    saved.messageId(), saved.seq(), saved.content(), session.getSummary(), history);
        }

        // M5-5.1 分析埋点：每条用户消息都发（聚合侧取最早即为「首条用户消息」，
        // 从而无需在会话域判定「是否首条」）
        if ("user".equalsIgnoreCase(request.getSender())) {
            analyticsPublisher.publishLifecycle(session.getId(), session.getTenantId(),
                    AnalyticsEventPublisher.STAGE_USER_MSG, session.getChannel(),
                    session.getUserId(), session.getAgentId(), saved.messageId());
        }

        observer.onNext(lingxi.session.v1.Session.SendMessageResponse.newBuilder()
                .setMessageId(saved.messageId())
                .setSeq(saved.seq())
                .setTimestampMs(saved.timestampMillis())
                .build());
        observer.onCompleted();
    }

    @Override
    public void changeState(lingxi.session.v1.Session.ChangeStateRequest request,
                            StreamObserver<lingxi.session.v1.Session.ChangeStateResponse> observer) {
        Session session = requireSession(request.getSessionId(), observer);
        if (session == null) {
            return;
        }
        SessionStateMachine.State target = fromProtoState(request.getTargetState());
        if (target == null) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("非法目标状态").asRuntimeException());
            return;
        }
        try {
            session.changeState(target);
            // 状态变为人工接管时记录坐席（M2-B 由接管分配器设置，此处保留扩展点）
            if (target == SessionStateMachine.State.HUMAN_CHAT && !request.getOperator().isBlank()) {
                session.setAgentId(request.getOperator());
            }
            observer.onNext(lingxi.session.v1.Session.ChangeStateResponse.newBuilder()
                    .setOk(true)
                    .setCurrentState(toProtoState(session.getState()))
                    .build());
            observer.onCompleted();
            // M4-4.5：成功迁移到 HUMAN_CHAT 时通知 AI 域终止正在生成的回答
            if (target == SessionStateMachine.State.HUMAN_CHAT) {
                eventPublisher.publishTakeover(session.getId(), "state_change", request.getOperator());
                // M5-5.1 分析埋点：状态迁至人工视为「转人工」（漏斗 handoff 阶段）
                analyticsPublisher.publishLifecycle(session.getId(), session.getTenantId(),
                        AnalyticsEventPublisher.STAGE_HANDOFF, session.getChannel(),
                        session.getUserId(), session.getAgentId(), "");
            }
            // M5-5.1 分析埋点：关闭（漏斗终态）
            if (target == SessionStateMachine.State.CLOSED) {
                analyticsPublisher.publishLifecycle(session.getId(), session.getTenantId(),
                        AnalyticsEventPublisher.STAGE_CLOSED, session.getChannel(),
                        session.getUserId(), session.getAgentId(), "");
            }
        } catch (IllegalStateTransitionException e) {
            log.warn("非法状态迁移被拒绝 sessionId={} {} -> {}", session.getId(),
                    session.getState(), target);
            observer.onNext(lingxi.session.v1.Session.ChangeStateResponse.newBuilder()
                    .setOk(false)
                    .setReason(e.getMessage())
                    .setCurrentState(toProtoState(session.getState()))
                    .build());
            observer.onCompleted();
        }
    }

    @Override
    public void pullMessages(lingxi.session.v1.Session.PullMessagesRequest request,
                             StreamObserver<lingxi.session.v1.Session.PullMessagesResponse> observer) {
        Session session = requireSession(request.getSessionId(), observer);
        if (session == null) {
            return;
        }
        int limit = request.getLimit() > 0 ? Math.min(request.getLimit(), 100) : 50;
        List<SessionMessage> missed = session.pullAfter(request.getLastSeq(), limit);

        lingxi.session.v1.Session.PullMessagesResponse.Builder builder =
                lingxi.session.v1.Session.PullMessagesResponse.newBuilder();
        for (SessionMessage m : missed) {
            builder.addMessages(toProtoMessage(m));
        }
        builder.setHasMore(missed.size() == limit);
        observer.onNext(builder.build());
        observer.onCompleted();
    }

    /**
     * 当前状态是否允许 AI 应答（M3-3.11）：INIT（尚未进入 AI）与 AI_ACTIVE（AI 会话中）。
     * HUMAN_CHAT（坐席已接管）与 CLOSED 下 AI 退出，不再触发。
     */
    private boolean aiAnswerable(Session session) {
        SessionStateMachine.State state = session.getState();
        return state == SessionStateMachine.State.INIT || state == SessionStateMachine.State.AI_ACTIVE;
    }

    /** 校验建会话请求参数。 */
    private void validateCreateRequest(lingxi.session.v1.Session.CreateSessionRequest request) {
        if (request.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (request.getChannel().isBlank()) {
            throw new IllegalArgumentException("channel 不能为空");
        }
    }

    @Override
    public void listWaitingSessions(lingxi.session.v1.Session.ListWaitingSessionsRequest request,
                                    StreamObserver<lingxi.session.v1.Session.ListWaitingSessionsResponse> observer) {
        int limit = request.getLimit() > 0 ? Math.min(request.getLimit(), 50) : 20;
        // M3-3.11：INIT（待接入）与 AI_ACTIVE（AI 应答中）都可人工介入，均入客服队列；
        // M4-4.5：WAITING_HUMAN（AI 请求转人工）仍在队列等待接入
        List<lingxi.session.v1.Session.SessionSummary> summaries =
                repository.listByStates(EnumSet.of(
                                SessionStateMachine.State.INIT, SessionStateMachine.State.AI_ACTIVE,
                                SessionStateMachine.State.WAITING_HUMAN)).stream()
                        .limit(limit)
                        .map(this::toProtoSummary)
                        .toList();
        observer.onNext(lingxi.session.v1.Session.ListWaitingSessionsResponse.newBuilder()
                .addAllSessions(summaries)
                .build());
        observer.onCompleted();
    }

    @Override
    public void takeOver(lingxi.session.v1.Session.TakeOverRequest request,
                         StreamObserver<lingxi.session.v1.Session.TakeOverResponse> observer) {
        Session session = requireSession(request.getSessionId(), observer);
        if (session == null) {
            return;
        }
        Session.TakeOverResult result = session.takeOver(request.getAgentId());
        log.info("坐席接管 {} sessionId={} agentId={}", result.ok() ? "成功" : "失败",
                session.getId(), request.getAgentId());
        observer.onNext(lingxi.session.v1.Session.TakeOverResponse.newBuilder()
                .setOk(result.ok())
                .setReason(result.reason() == null ? "" : result.reason())
                .setState(toProtoState(session.getState()))
                .build());
        observer.onCompleted();
        // M4-4.5：接管成功后通知 AI 域终止该会话正在生成的回答
        if (result.ok()) {
            eventPublisher.publishTakeover(session.getId(), "agent_takeover", request.getAgentId());
            // M5-5.1 分析埋点：坐席接管成功亦属「转人工」（漏斗 handoff 阶段，聚合侧取最早）
            analyticsPublisher.publishLifecycle(session.getId(), session.getTenantId(),
                    AnalyticsEventPublisher.STAGE_HANDOFF, session.getChannel(),
                    session.getUserId(), session.getAgentId(), "");
        }
    }

    /**
     * 提交满意度评价（M5-5.1）：**租户/用户由会话解析**（前端只传 session_id 与评分，
     * 防伪造租户）；校验会话存在与评分范围后发布分析事件，由报表域落库。
     */
    @Override
    public void submitFeedback(lingxi.session.v1.Session.SubmitFeedbackRequest request,
                               StreamObserver<lingxi.session.v1.Session.SubmitFeedbackResponse> observer) {
        Session session = repository.findById(request.getSessionId()).orElse(null);
        if (session == null) {
            observer.onNext(lingxi.session.v1.Session.SubmitFeedbackResponse.newBuilder()
                    .setOk(false).setReason("会话不存在").build());
            observer.onCompleted();
            return;
        }
        int rating = request.getRating();
        if (rating < 1 || rating > MAX_RATING) {
            observer.onNext(lingxi.session.v1.Session.SubmitFeedbackResponse.newBuilder()
                    .setOk(false).setReason("评分必须在 1~" + MAX_RATING + " 之间").build());
            observer.onCompleted();
            return;
        }
        String comment = truncate(request.getComment(), MAX_COMMENT_LEN);
        analyticsPublisher.publishFeedback(session.getId(), session.getTenantId(), session.getUserId(),
                rating, comment, truncate(request.getTags(), MAX_TAGS_LEN));
        log.info("会话满意度已提交 sessionId={} rating={}", session.getId(), rating);
        observer.onNext(lingxi.session.v1.Session.SubmitFeedbackResponse.newBuilder().setOk(true).build());
        observer.onCompleted();
    }

    /** 按上限截断文本（null 安全），避免超长评价撑爆事件体与存储列。 */
    private static String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    @Override
    public void listAgentSessions(lingxi.session.v1.Session.ListAgentSessionsRequest request,
                                  StreamObserver<lingxi.session.v1.Session.ListAgentSessionsResponse> observer) {
        List<lingxi.session.v1.Session.SessionSummary> summaries =
                repository.listByAgent(request.getAgentId()).stream()
                        .map(this::toProtoSummary)
                        .toList();
        observer.onNext(lingxi.session.v1.Session.ListAgentSessionsResponse.newBuilder()
                .addAllSessions(summaries)
                .build());
        observer.onCompleted();
    }

    @Override
    public void suggestReplies(lingxi.session.v1.Session.SuggestRepliesRequest request,
                               StreamObserver<lingxi.session.v1.Session.SuggestRepliesResponse> observer) {
        // 薄委托：上下文组装与降级/入队逻辑集中在协调器，gRPC 层只做参数补全与异常映射
        String requestId = request.getRequestId().isBlank()
                ? UUID.randomUUID().toString() : request.getRequestId();
        try {
            SuggestResult result = suggestCoordinator.suggest(requestId, request.getSessionId(),
                    request.getAgentId(), request.getIntent(), request.getMaxSuggestions());
            observer.onNext(lingxi.session.v1.Session.SuggestRepliesResponse.newBuilder()
                    .setOk(result.ok())
                    .setDegraded(result.degraded())
                    .setDegradeReason(result.degradeReason() == null ? "" : result.degradeReason())
                    .setQueued(result.queued())
                    .setRequestId(requestId)
                    .addAllSuggestions(result.suggestions())
                    .setElapsedMs(result.elapsedMs())
                    .setHistoryCount(result.historyCount())
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException e) {
            observer.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (IllegalStateException e) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /** 领域会话 → proto 会话摘要（工作台列表用）。 */
    private lingxi.session.v1.Session.SessionSummary toProtoSummary(Session s) {
        return lingxi.session.v1.Session.SessionSummary.newBuilder()
                .setSessionId(s.getId())
                .setUserId(s.getUserId())
                .setState(toProtoState(s.getState()))
                .setAgentId(s.getAgentId() == null ? "" : s.getAgentId())
                .setCreatedAtMs(s.getCreatedAt().toEpochMilli())
                .setUpdatedAtMs(s.getUpdatedAt().toEpochMilli())
                .setMessageCount(s.messageCount())
                // M4-4.5 摘要移交：随客服队列下发摘要预览（截断后），供坐席接管前了解上下文
                .setSummary(preview(s.getSummary()))
                .build();
    }

    /** 摘要预览：超长按配置截断，避免客服队列列表 payload 膨胀。 */
    private String preview(String summary) {
        if (summary == null) {
            return "";
        }
        return summary.length() > summaryPreviewLen ? summary.substring(0, summaryPreviewLen) : summary;
    }

    /** 按会话 ID 查找，不存在时向客户端返回 NOT_FOUND。 */
    private Session requireSession(String sessionId, StreamObserver<?> observer) {
        if (sessionId.isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("sessionId 不能为空").asRuntimeException());
            return null;
        }
        var session = repository.findById(sessionId);
        if (session.isEmpty()) {
            observer.onError(Status.NOT_FOUND.withDescription("会话不存在: " + sessionId).asRuntimeException());
            return null;
        }
        return session.get();
    }

    /** 本地状态 → proto 枚举。 */
    private lingxi.session.v1.Session.SessionState toProtoState(SessionStateMachine.State state) {
        return switch (state) {
            case INIT -> lingxi.session.v1.Session.SessionState.INIT;
            case AI_ACTIVE -> lingxi.session.v1.Session.SessionState.AI_ACTIVE;
            case WAITING_HUMAN -> lingxi.session.v1.Session.SessionState.WAITING_HUMAN;
            case HUMAN_CHAT -> lingxi.session.v1.Session.SessionState.HUMAN_CHAT;
            case CLOSED -> lingxi.session.v1.Session.SessionState.CLOSED;
        };
    }

    /** proto 枚举 → 本地状态，未知值返回 null。 */
    private SessionStateMachine.State fromProtoState(lingxi.session.v1.Session.SessionState state) {
        return switch (state) {
            case INIT -> SessionStateMachine.State.INIT;
            case AI_ACTIVE -> SessionStateMachine.State.AI_ACTIVE;
            case WAITING_HUMAN -> SessionStateMachine.State.WAITING_HUMAN;
            case HUMAN_CHAT -> SessionStateMachine.State.HUMAN_CHAT;
            case CLOSED -> SessionStateMachine.State.CLOSED;
            default -> null;
        };
    }

    /** 领域消息 → proto 消息。 */
    private lingxi.session.v1.Session.SessionMessage toProtoMessage(SessionMessage m) {
        return lingxi.session.v1.Session.SessionMessage.newBuilder()
                .setMessageId(m.messageId())
                .setSessionId(m.sessionId())
                .setSender(m.sender())
                .setSenderId(m.senderId())
                .setContent(m.content())
                .setSeq(m.seq())
                .setTimestampMs(m.timestampMillis())
                .build();
    }
}
