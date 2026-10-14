package com.lingxi.session.repo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.lingxi.session.model.Session;
import com.lingxi.session.state.SessionStateMachine.State;

/**
 * 会话存储抽象：M2 提供内存实现，后续替换为 MySQL 实现（消息按月分表）。
 * 接口隔离保证业务层不依赖具体存储。
 */
public interface SessionRepository {

    Session save(Session session);

    Optional<Session> findById(String sessionId);

    /** 按状态查询会话（客服队列：待接管 = INIT）。 */
    List<Session> listByState(State state);

    /** 按状态集合查询会话（客服队列：INIT + AI_ACTIVE 均属「可人工介入」，M3-3.11）。 */
    List<Session> listByStates(Set<State> states);

    /** 按接管坐席查询会话。 */
    List<Session> listByAgent(String agentId);
}
