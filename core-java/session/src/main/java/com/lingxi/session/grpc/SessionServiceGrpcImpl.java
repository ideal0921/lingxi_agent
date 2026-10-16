package com.lingxi.session.grpc;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.lingxi.common.tenant.IsolationViolation;
import com.lingxi.common.tenant.MissingTenantContextException;
import com.lingxi.common.tenant.TenantAccessDeniedException;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;
import com.lingxi.common.tenant.TenantIsolationAuditor;
import com.lingxi.common.tenant.TenantIsolationGuard;
import com.lingxi.session.ai.SuggestReplyCoordinator;
import com.lingxi.session.ai.SuggestResult;
import com.lingxi.session.event.AnalyticsEventPublisher;
import com.lingxi.session.event.SessionEventPublisher;
import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.model.SessionOverview;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.session.state.IllegalStateTransitionException;
import com.lingxi.session.state.SessionStateMachine;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lingxi.session.v1.SessionServiceGrpc;

/**
 * 会话域 gRPC 服务实现：网关通过此接口完成建会话、发消息、状态迁移与断连续传。
 * 非法迁移与重复关闭被拒绝并返回原因。
 *
 * <p><b>租户隔离（M5-5.5）</b>：租户一律取自信任上下文（Go 网关经 metadata 下传，
 * 由 {@code TenantGuardInterceptor} 注入 {@link TenantContext}）；</p>
 * <ul>
 *   <li>建会话拒绝「请求体租户 ≠ 身份租户」的伪造请求，并计入隔离审计；</li>
 *   <li>以会话 ID 为入口的所有操作（发消息/迁状态/拉消息/接管/评价/建议）先做归属校验，
 *       越权统一按「不存在」对外返回（避免枚举探测），真实事实落审计。</li>
 * </ul>
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
    /** 历史会话列表默认条数（M5-5.7）。 */
    private static final int DEFAULT_LIST_LIMIT = 20;
    /** 历史会话列表条数上限（M5-5.7）：前端可请求更大值，但服务端收敛，避免大 payload。 */
    private static final int MAX_LIST_LIMIT = 50;

    /** 审计动作名（数据隔离审计维度）。 */
    private static final String RESOURCE_SESSION = "session";
    private static final String ACTION_CREATE_SESSION = "create_session";
    private static final String ACTION_SEND_MESSAGE = "send_message";
    private static final String ACTION_CHANGE_STATE = "change_state";
    private static final String ACTION_PULL_MESSAGES = "pull_messages";
    private static final String ACTION_TAKE_OVER = "take_over";
    private static final String ACTION_FEEDBACK = "submit_feedback";

    private final SessionRepository repository;
    private final SessionEventPublisher eventPublisher;
    /** 随事件带给 AI 域的「近 N 轮」消息条数上限（M3-3.11 上下文窗口）。 */
    private final int historyLimit;
    /** 客服队列下发摘要预览的截断长度（M4-4.5 摘要移交），避免列表 payload 膨胀。 */
    private final int summaryPreviewLen;
    /** 历史会话列表 last_preview 的截断长度（M5-5.7），避免长消息全量回传。 */
    private final int listPreviewLen;
    /** 坐席快捷回复协调器（M4-4.6）：组装上下文并同步调 AI 生成候选。 */
    private final SuggestReplyCoordinator suggestCoordinator;
    /** 分析埋点发布端口（M5-5.1）：会话生命周期与满意度事件。 */
    private final AnalyticsEventPublisher analyticsPublisher;
    /** 数据隔离审计端口（M5-5.5）：跨租户越权拒绝事实。 */
    private final TenantIsolationAuditor isolationAuditor;

    public SessionServiceGrpcImpl(SessionRepository repository, SessionEventPublisher eventPublisher,
                                  @Value("${lingxi.session.history-limit:20}") int historyLimit,
                                  @Value("${lingxi.session.summary-preview-len:200}") int summaryPreviewLen,
                                  @Value("${lingxi.session.list-preview-len:60}") int listPreviewLen,
                                  SuggestReplyCoordinator suggestCoordinator,
                                  AnalyticsEventPublisher analyticsPublisher,
                                  TenantIsolationAuditor isolationAuditor) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.historyLimit = historyLimit;
        this.summaryPreviewLen = summaryPreviewLen;
        this.listPreviewLen = listPreviewLen;
        this.suggestCoordinator = suggestCoordinator;
        this.analyticsPublisher = analyticsPublisher;
        this.isolationAuditor = isolationAuditor;
    }

    @Override
    public void createSession(lingxi.session.v1.Session.CreateSessionRequest request,
                              StreamObserver<lingxi.session.v1.Session.CreateSessionResponse> observer) {
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return;
        }
        // 请求体租户仅作兼容字段：与可信租户不一致即视为伪造，拒绝并审计
        if (!request.getTenantId().isBlank() && !tenantId.equals(request.getTenantId())) {
            reportViolation(RESOURCE_SESSION, "(new)", request.getTenantId(), ACTION_CREATE_SESSION,
                    "请求体租户与身份租户不一致（疑似伪造）");
            observer.onError(Status.PERMISSION_DENIED
                    .withDescription("请求租户与身份租户不一致").asRuntimeException());
            return;
        }
        try {
            validateCreateRequest(request);
            // 归属用户一律以可信主体为准（与租户同一原则）：请求体 user_id 仅作兼容字段。
            // 否则①可把会话挂到他人名下（越权）；②「按本人主体查历史会话」会因用户键不同源而查不到。
            String userId = trustedUserIdOr(request.getUserId());
            Session session = new Session(tenantId, userId, request.getChannel());
            repository.save(session);
            log.info("创建会话 sessionId={} tenantId={} userId={} channel={}",
                    session.getId(), tenantId, userId, request.getChannel());
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
        Session session = requireOwnedSession(request.getSessionId(), ACTION_SEND_MESSAGE, observer);
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
        // 消息草稿：messageId/时间戳由服务层生成，seq 交由存储侧在会话行锁下权威分配
        String messageId = UUID.randomUUID().toString();
        Instant messageAt = Instant.now();

        // 用户消息落库后广播事件，触发 AI 流式应答（M3-3.8 异步主路径）。
        // M3-3.11：仅在可通过 AI 应答的状态（INIT / AI_ACTIVE）下触发；座席已接管
        // （HUMAN_CHAT）后 AI 退出，不再应答；坐席回复不触发（避免循环应答）。
        boolean aiAnswerable = "user".equalsIgnoreCase(request.getSender()) && aiAnswerable(session);
        if (aiAnswerable && session.getState() == SessionStateMachine.State.INIT) {
            session.changeState(SessionStateMachine.State.AI_ACTIVE);  // 用户消息触发 AI：进入 AI 应答中
            log.info("会话 {} 进入 AI_ACTIVE", session.getId());
        }
        // M5-5.7：先把会话状态/列显式落库（不含消息与 message_count）；再把消息交存储侧
        // 追加并取回**权威 seq**——不再信任聚合算出的 seq 回给客户端/写事件，否则并发会覆盖丢消息
        repository.save(session);
        int seq = repository.appendMessage(session.getId(), new SessionMessage(
                messageId, session.getId(), request.getSender(), request.getSenderId(), content, 0L, messageAt));
        log.info("会话 {} 收到消息 seq={} sender={} 历史条数={} 摘要长度={}", session.getId(),
                seq, request.getSender(), history.size(), session.getSummary().length());

        if (aiAnswerable) {
            eventPublisher.publishUserMessage(session.getId(), session.getTenantId(), session.getUserId(),
                    messageId, seq, content, session.getSummary(), history);
        }

        // M5-5.1 分析埋点：每条用户消息都发（聚合侧取最早即为「首条用户消息」，
        // 从而无需在会话域判定「是否首条」）
        if ("user".equalsIgnoreCase(request.getSender())) {
            analyticsPublisher.publishLifecycle(session.getId(), session.getTenantId(),
                    AnalyticsEventPublisher.STAGE_USER_MSG, session.getChannel(),
                    session.getUserId(), session.getAgentId(), messageId);
        }

        observer.onNext(lingxi.session.v1.Session.SendMessageResponse.newBuilder()
                .setMessageId(messageId)
                .setSeq(seq)
                .setTimestampMs(messageAt.toEpochMilli())
                .build());
        observer.onCompleted();
    }

    @Override
    public void changeState(lingxi.session.v1.Session.ChangeStateRequest request,
                            StreamObserver<lingxi.session.v1.Session.ChangeStateResponse> observer) {
        Session session = requireOwnedSession(request.getSessionId(), ACTION_CHANGE_STATE, observer);
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
            // M5-5.7：状态与坐席绑定显式落库，避免切 MySQL 后迁移结果只留在内存对象里
            repository.save(session);
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
        Session session = requireOwnedSession(request.getSessionId(), ACTION_PULL_MESSAGES, observer);
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

    /**
     * 会话归属用户：以可信 metadata 主体为准；仅当上下文无用户主体时（如鉴权关闭的本地开发）
     * 才回落请求体取值——「谁建的会话」不能交给前端决定。
     */
    private String trustedUserIdOr(String requested) {
        String trusted = TenantContext.current().userId();
        if (trusted == null || trusted.isBlank()) {
            return requested;
        }
        if (!trusted.equals(requested)) {
            log.warn("建会话请求体 userId={} 与可信主体不一致，已按可信主体归属", requested);
        }
        return trusted;
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
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return;
        }
        int limit = request.getLimit() > 0 ? Math.min(request.getLimit(), 50) : 20;
        // M3-3.11：INIT（待接入）与 AI_ACTIVE（AI 应答中）都可人工介入，均入客服队列；
        // M4-4.5：WAITING_HUMAN（AI 请求转人工）仍在队列等待接入
        // M5-5.5：队列按租户隔离，坐席只能看到本租户会话
        List<lingxi.session.v1.Session.SessionSummary> summaries =
                repository.listByStates(EnumSet.of(
                                SessionStateMachine.State.INIT, SessionStateMachine.State.AI_ACTIVE,
                                SessionStateMachine.State.WAITING_HUMAN), tenantId).stream()
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
        Session session = requireOwnedSession(request.getSessionId(), ACTION_TAKE_OVER, observer);
        if (session == null) {
            return;
        }
        Session.TakeOverResult result = session.takeOver(request.getAgentId());
        if (result.ok()) {
            // M5-5.7：接管成功（状态 HUMAN_CHAT + 坐席绑定）显式落库，重启后接管关系不丢
            repository.save(session);
        }
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
     *
     * <p>M5-5.5：会话须归属当前身份租户；越权按业务失败返回（不泄露存在性），并落隔离审计。</p>
     */
    @Override
    public void submitFeedback(lingxi.session.v1.Session.SubmitFeedbackRequest request,
                               StreamObserver<lingxi.session.v1.Session.SubmitFeedbackResponse> observer) {
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return;
        }
        Optional<Session> found = repository.findById(request.getSessionId());
        if (found.isPresent() && !found.get().getTenantId().equals(tenantId)) {
            reportViolation(RESOURCE_SESSION, request.getSessionId(), found.get().getTenantId(),
                    ACTION_FEEDBACK, "会话归属其他租户");
            rejectNotFoundBusiness(observer);
            return;
        }
        Session session = found.orElse(null);
        if (session == null) {
            rejectNotFoundBusiness(observer);
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
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return;
        }
        // M5-5.5：坐席会话列表同样按租户过滤，避免跨租户会话泄露
        List<lingxi.session.v1.Session.SessionSummary> summaries =
                repository.listByAgent(request.getAgentId(), tenantId).stream()
                        .map(this::toProtoSummary)
                        .toList();
        observer.onNext(lingxi.session.v1.Session.ListAgentSessionsResponse.newBuilder()
                .addAllSessions(summaries)
                .build());
        observer.onCompleted();
    }

    /**
     * 列「我」的历史会话（M5-5.7）：**租户与用户一律取自可信 metadata**（请求体不含二者，
     * 故无法以参数枚举他人会话）；按最近活跃倒序，limit 默认 {@value #DEFAULT_LIST_LIMIT}、
     * 上限 {@value #MAX_LIST_LIMIT}；列表只返回轻量字段与最后一条消息的截断预览。
     */
    @Override
    public void listSessionsByUser(lingxi.session.v1.Session.ListSessionsByUserRequest request,
                                   StreamObserver<lingxi.session.v1.Session.ListSessionsByUserResponse> observer) {
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return;
        }
        String userId = TenantContext.current().userId();
        if (userId.isBlank()) {
            // 令牌无用户主体（匿名接入）：无归属会话可列，返回空列表而非报错
            log.warn("列历史会话缺少用户身份，返回空列表 tenantId={}", tenantId);
            observer.onNext(lingxi.session.v1.Session.ListSessionsByUserResponse.getDefaultInstance());
            observer.onCompleted();
            return;
        }
        int limit = request.getLimit() > 0 ? Math.min(request.getLimit(), MAX_LIST_LIMIT) : DEFAULT_LIST_LIMIT;
        List<lingxi.session.v1.Session.SessionListItem> items =
                repository.listByUser(userId, tenantId, limit).stream()
                        .map(this::toProtoListItem)
                        .toList();
        observer.onNext(lingxi.session.v1.Session.ListSessionsByUserResponse.newBuilder()
                .addAllSessions(items)
                .build());
        observer.onCompleted();
    }

    /** 列表项 → proto：预览按展示长度截断，避免长消息全量回传。 */
    private lingxi.session.v1.Session.SessionListItem toProtoListItem(SessionOverview overview) {
        return lingxi.session.v1.Session.SessionListItem.newBuilder()
                .setSessionId(overview.sessionId())
                .setUserId(overview.userId())
                .setChannel(overview.channel())
                .setState(toProtoState(overview.state()))
                .setCreatedAtMs(overview.createdAtMs())
                .setLastActiveAtMs(overview.lastActiveAtMs())
                .setMessageCount(overview.messageCount())
                .setLastPreview(truncate(overview.lastPreview(), listPreviewLen))
                .build();
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
        } catch (MissingTenantContextException e) {
            observer.onError(Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
        } catch (TenantAccessDeniedException e) {
            // M5-5.5：越权统一按「会话不存在」返回，真实事实落审计
            TenantIsolationGuard.report(isolationAuditor, e.violation());
            observer.onError(Status.NOT_FOUND.withDescription("会话不存在").asRuntimeException());
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

    /**
     * 租户内按会话 ID 查找并通过归属校验；异常情况直接向客户端返回错误。
     *
     * <p>越权对外表现为 {@code NOT_FOUND}（与「不存在」一致），避免以错误码差异枚举他人资源；
     * 真实越权事实写入隔离审计。</p>
     *
     * @return 通过校验的会话；校验失败返回 null（观察者已收到错误）
     */
    private Session requireOwnedSession(String sessionId, String action, StreamObserver<?> observer) {
        if (sessionId == null || sessionId.isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("sessionId 不能为空").asRuntimeException());
            return null;
        }
        String tenantId = trustedTenantId(observer);
        if (tenantId == null) {
            return null;
        }
        Optional<Session> found = repository.findById(sessionId);
        if (found.isEmpty()) {
            observer.onError(Status.NOT_FOUND.withDescription("会话不存在: " + sessionId).asRuntimeException());
            return null;
        }
        Session session = found.get();
        if (!session.getTenantId().equals(tenantId)) {
            reportViolation(RESOURCE_SESSION, sessionId, session.getTenantId(), action, "会话归属其他租户");
            observer.onError(Status.NOT_FOUND.withDescription("会话不存在: " + sessionId).asRuntimeException());
            return null;
        }
        return session;
    }

    /** 读取可信租户；缺失时返回 null 并已回 {@code UNAUTHENTICATED}。 */
    private String trustedTenantId(StreamObserver<?> observer) {
        try {
            return TenantContext.requireTenantId();
        } catch (MissingTenantContextException e) {
            // 正常经网关调用不会走到这里（拦截器已强制）；直连 Java 且未带 metadata 时兜底拒绝
            log.warn("会话域调用缺少租户上下文已拒绝：{}", e.getMessage());
            observer.onError(Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
            return null;
        }
    }

    /** 上报一次跨租户越权（审计失败不影响主链路）。 */
    private void reportViolation(String resourceType, String resourceId, String resourceTenantId,
                                 String action, String detail) {
        TenantIdentity identity = TenantContext.current();
        IsolationViolation violation = TenantIsolationGuard.violationOf(
                identity, resourceType, resourceId, resourceTenantId, action, detail);
        TenantIsolationGuard.report(isolationAuditor, violation);
    }

    /** 满意度评价的「会话不存在/越权」业务失败应答（不泄露存在性）。 */
    private void rejectNotFoundBusiness(StreamObserver<lingxi.session.v1.Session.SubmitFeedbackResponse> observer) {
        observer.onNext(lingxi.session.v1.Session.SubmitFeedbackResponse.newBuilder()
                .setOk(false).setReason("会话不存在").build());
        observer.onCompleted();
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
