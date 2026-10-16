package com.lingxi.session.repo;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.model.SessionOverview;
import com.lingxi.session.state.SessionStateMachine.State;

/**
 * 内存会话存储：以 ConcurrentHashMap 保证并发安全，供单测与
 * {@code lingxi.session.store=inmemory}（无 MySQL 的本地/轻量运行环境）使用。
 *
 * <p>默认走 {@link MySqlSessionRepository}（{@code lingxi.session.store=mysql}）；
 * 两者对 {@link SessionRepository} 端口语义一致——状态机迁移、接管、队列/坐席列表、
 * 增量拉取与消息 seq 递增均由 {@link Session} 聚合保证，本类只负责存取。</p>
 */
@Repository
@ConditionalOnProperty(name = "lingxi.session.store", havingValue = "inmemory")
public class InMemorySessionRepository implements SessionRepository {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session save(Session session) {
        sessions.put(session.getId(), session);
        return session;
    }

    @Override
    public int appendMessage(String sessionId, SessionMessage draft) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        // 内存实现缓存的是同一个 Session 对象，其 appendMessage 已 synchronized，
        // 等价于存储侧串行分配权威 seq（与 MySQL 实现的语义保持一致）
        return (int) session.appendMessage(draft.sender(), draft.senderId(), draft.content(),
                draft.messageId()).seq();
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public Optional<Session> findById(String sessionId, String tenantId) {
        Session session = sessions.get(sessionId);
        // 租户不匹配视为不存在：不泄露「资源存在但不属于你」的信息
        if (session == null || !session.getTenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    @Override
    public List<Session> listByStates(Set<State> states, String tenantId) {
        return sessions.values().stream()
                .filter(s -> tenantId.equals(s.getTenantId()))
                .filter(s -> states.contains(s.getState()))
                // 客服队列按进入时间先后（FIFO），与 MySQL 实现保持一致
                .sorted(Comparator.comparing(Session::getCreatedAt))
                .toList();
    }

    @Override
    public List<Session> listByAgent(String agentId, String tenantId) {
        return sessions.values().stream()
                .filter(s -> tenantId.equals(s.getTenantId()))
                .filter(s -> agentId.equals(s.getAgentId()))
                .sorted(Comparator.comparing(Session::getUpdatedAt).reversed())
                .toList();
    }

    @Override
    public List<SessionOverview> listByUser(String userId, String tenantId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return sessions.values().stream()
                .filter(s -> tenantId.equals(s.getTenantId()))
                .filter(s -> userId.equals(s.getUserId()))
                .sorted(Comparator.comparing(Session::getUpdatedAt).reversed())
                .limit(limit)
                .map(InMemorySessionRepository::toOverview)
                .toList();
    }

    /** 会话 → 列表项：预览取最后一条消息（无消息为空串）。 */
    private static SessionOverview toOverview(Session session) {
        List<SessionMessage> last = session.recentMessages(1);
        String preview = last.isEmpty() ? "" : last.get(0).content();
        return new SessionOverview(session.getId(), session.getUserId(), session.getChannel(),
                session.getState(), session.getCreatedAt().toEpochMilli(),
                session.getUpdatedAt().toEpochMilli(), session.messageCount(), preview);
    }
}
