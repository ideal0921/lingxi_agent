package com.lingxi.session.repo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.model.SessionOverview;
import com.lingxi.session.state.SessionStateMachine.State;

/**
 * 会话存储抽象：M2 提供内存实现，后续替换为 MySQL 实现（消息按月分表）。
 * 接口隔离保证业务层不依赖具体存储。
 *
 * <p>M5-5.5 租户隔离：列表类查询一律**要求租户入参**，从数据源侧过滤，
 * 避免客服队列/坐席会话列表跨租户串数据。</p>
 *
 * <p><b>消息 seq 由存储侧分配</b>：{@link #appendMessage} 是消息写入的唯一入口，
 * 其返回值为权威 seq。调用方不得再依赖 {@link Session} 聚合算出的 seq——那属于
 * 「应用侧读-改-写」，并发下会算出同一 seq 而互相覆盖丢消息。</p>
 */
public interface SessionRepository {

    Session save(Session session);

    /**
     * 追加一条消息，返回**由存储侧分配的权威 seq**（同一会话内从 1 起单调递增）。
     *
     * <p>存储侧在同一会话行上加锁后分配 seq：同一会话串行、不同会话互不阻塞。</p>
     *
     * @param sessionId 会话 ID；不存在时抛 {@link IllegalArgumentException}
     * @param draft     消息草稿（复用 {@link SessionMessage}，仅取 sender / senderId /
     *                  content / messageId / timestamp 字段；seq 与 sessionId 由存储侧决定）
     * @return 存储侧分配的权威 seq
     */
    int appendMessage(String sessionId, SessionMessage draft);

    /** 按会话 ID 查询（不带租户条件）。调用方**必须**自行做租户归属校验，仅限内部/测试使用。 */
    Optional<Session> findById(String sessionId);

    /** 租户内按会话 ID 查询：租户不匹配或不存在时返回空。 */
    Optional<Session> findById(String sessionId, String tenantId);

    /** 租户内按状态集合查询（客服队列：INIT + AI_ACTIVE + WAITING_HUMAN）。 */
    List<Session> listByStates(Set<State> states, String tenantId);

    /** 租户内按接管坐席查询会话。 */
    List<Session> listByAgent(String agentId, String tenantId);

    /**
     * 租户内按用户列出会话（M5-5.7 历史会话列表）：按最近活跃倒序，最多 {@code limit} 条。
     * 只返回列表轻量字段（含最后一条消息预览），不回表读消息体。
     */
    List<SessionOverview> listByUser(String userId, String tenantId, int limit);
}
