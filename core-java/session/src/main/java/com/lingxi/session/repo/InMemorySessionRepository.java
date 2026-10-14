package com.lingxi.session.repo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.lingxi.session.model.Session;
import com.lingxi.session.state.SessionStateMachine.State;

/**
 * 内存会话存储：以 ConcurrentHashMap 保证并发安全。
 * 仅为 M2 打通链路使用；生产环境切换为 MySQL 实现（保持接口不变）。
 */
@Repository
public class InMemorySessionRepository implements SessionRepository {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session save(Session session) {
        sessions.put(session.getId(), session);
        return session;
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<Session> listByState(State state) {
        return sessions.values().stream()
                .filter(s -> s.getState() == state)
                .toList();
    }

    @Override
    public List<Session> listByStates(Set<State> states) {
        return sessions.values().stream()
                .filter(s -> states.contains(s.getState()))
                .toList();
    }

    @Override
    public List<Session> listByAgent(String agentId) {
        return sessions.values().stream()
                .filter(s -> agentId.equals(s.getAgentId()))
                .toList();
    }
}
