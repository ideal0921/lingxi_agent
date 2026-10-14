package com.lingxi.session.grpc;

import java.util.EnumSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    private final SessionRepository repository;
    private final SessionEventPublisher eventPublisher;
    /** 随事件带给 AI 域的「近 N 轮」消息条数上限（M3-3.11 上下文窗口）。 */
    private final int historyLimit;

    public SessionServiceGrpcImpl(SessionRepository repository, SessionEventPublisher eventPublisher,
                                  @Value("${lingxi.session.history-limit:20}") int historyLimit) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.historyLimit = historyLimit;
    }

    @Override
    public void createSession(lingxi.session.v1.Session.CreateSessionRequest request,
                              StreamObserver<lingxi.session.v1.Session.CreateSessionResponse> observer) {
        try {
            validateCreateRequest(request);
            Session session = new Session(request.getTenantId(), request.getUserId(), request.getChannel());
            repository.save(session);
            log.info("创建会话 sessionId={} userId={} channel={}", session.getId(), request.getUserId(), request.getChannel());

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
            eventPublisher.publishUserMessage(session.getId(), session.getTenantId(), saved.messageId(),
                    saved.seq(), saved.content(), session.getSummary(), history);
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
        // M3-3.11：INIT（待接入）与 AI_ACTIVE（AI 应答中）都可人工介入，均入客服队列
        List<lingxi.session.v1.Session.SessionSummary> summaries =
                repository.listByStates(EnumSet.of(
                                SessionStateMachine.State.INIT, SessionStateMachine.State.AI_ACTIVE)).stream()
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
                .build();
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
            case HUMAN_CHAT -> lingxi.session.v1.Session.SessionState.HUMAN_CHAT;
            case CLOSED -> lingxi.session.v1.Session.SessionState.CLOSED;
        };
    }

    /** proto 枚举 → 本地状态，未知值返回 null。 */
    private SessionStateMachine.State fromProtoState(lingxi.session.v1.Session.SessionState state) {
        return switch (state) {
            case INIT -> SessionStateMachine.State.INIT;
            case AI_ACTIVE -> SessionStateMachine.State.AI_ACTIVE;
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
