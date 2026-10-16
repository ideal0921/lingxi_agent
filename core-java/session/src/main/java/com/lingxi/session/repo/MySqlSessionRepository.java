package com.lingxi.session.repo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.model.SessionOverview;
import com.lingxi.session.state.SessionStateMachine.State;

/**
 * MySQL 会话存储（M5-5.7 持久化）：JdbcTemplate 直连 `lingxi_session` 与
 * `lingxi_session_message`（启动时 schema.sql 幂等建表）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>语义与 {@link InMemorySessionRepository} 对齐</b>：状态机迁移、接管、队列/坐席列表、
 *       增量拉取均由 {@link Session} 聚合保证，本类负责把聚合状态读写成行；</li>
 *   <li><b>消息 seq 由存储侧分配</b>：{@link #appendMessage} 在事务内对会话行加
 *       {@code FOR UPDATE} 锁后取 {@code message_count + 1} 作为权威 seq 落库，
 *       避免应用侧「读-改-写」并发算出同一 seq 互相覆盖丢消息；</li>
 *   <li><b>save 只落会话状态/列</b>：state / agent_id / summary / last_active_at_ms 的 upsert，
 *       **不再写 message_count、也不再落消息行**（消息唯一入口是 {@link #appendMessage}），
 *       以免陈旧聚合条数覆盖并发已追加的消息计数；</li>
 *   <li><b>租户隔离</b>：所有查询强制 {@code tenant_id} 条件，跨租户访问对外表现为「不存在」；</li>
 *   <li><b>可切换</b>：与 InMemory 实现经配置开关 {@code lingxi.session.store} 二选一装配，
 *       默认本实现（mysql）。</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "lingxi.session.store", havingValue = "mysql", matchIfMissing = true)
public class MySqlSessionRepository implements SessionRepository {

    private static final String SESSION_TABLE = "lingxi_session";
    private static final String MESSAGE_TABLE = "lingxi_session_message";

    /** 会话行 → 聚合（**不加载消息**）：列表类查询走此映射，避免回表读全部消息。 */
    private static final RowMapper<Session> SESSION_ROW_MAPPER = (rs, rowNum) -> Session.restore(
            rs.getString("session_id"), rs.getString("tenant_id"), rs.getString("user_id"),
            rs.getString("channel"), State.valueOf(rs.getString("state")), rs.getString("agent_id"),
            rs.getString("summary"), rs.getLong("created_at_ms"), rs.getLong("last_active_at_ms"),
            rs.getInt("message_count"), List.of());

    private static final RowMapper<SessionMessage> MESSAGE_ROW_MAPPER = (rs, rowNum) -> new SessionMessage(
            rs.getString("message_id"), rs.getString("session_id"), rs.getString("sender"),
            rs.getString("sender_id"), rs.getString("content"), rs.getLong("seq"),
            Instant.ofEpochMilli(rs.getLong("created_at_ms")));

    private final JdbcTemplate jdbcTemplate;

    public MySqlSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public Session save(Session session) {
        // 会话行 upsert：只更新状态与冗余列。**不写 message_count**——它由 appendMessage
        // 在行锁下权威维护；若此处以应用侧陈旧聚合条数覆盖，会把并发已追加的消息计数改小。
        jdbcTemplate.update("""
                INSERT INTO %s (session_id, tenant_id, user_id, channel, state, agent_id,
                                summary, created_at_ms, last_active_at_ms, message_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE state = VALUES(state), agent_id = VALUES(agent_id),
                    summary = VALUES(summary), last_active_at_ms = VALUES(last_active_at_ms)
                """.formatted(SESSION_TABLE),
                session.getId(), session.getTenantId(), session.getUserId(), session.getChannel(),
                session.getState().name(), nullToEmpty(session.getAgentId()), session.getSummary(),
                session.getCreatedAt().toEpochMilli(), session.getUpdatedAt().toEpochMilli(),
                session.messageCount());
        return session;
    }

    @Override
    @Transactional
    public int appendMessage(String sessionId, SessionMessage draft) {
        // 先给会话行加锁：同一会话的 seq 分配串行化（不同会话各锁各自的行，互不阻塞）。
        // seq 取自库内 message_count + 1（而非应用侧聚合算出的 seq），避免并发「读-改-写」算出同一 seq。
        Integer messageCount = jdbcTemplate.query(
                "SELECT message_count FROM " + SESSION_TABLE + " WHERE session_id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, sessionId);
        if (messageCount == null) {
            // 语义与既有实现一致：会话不存在即失败，不由仓储在写入时隐式建会话
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        int seq = messageCount + 1;
        // 行锁互斥保证 seq 不重复，故直接插入（不再用 ON DUPLICATE KEY UPDATE —— 那正是覆盖丢消息的来源）
        jdbcTemplate.update("""
                INSERT INTO %s (session_id, seq, message_id, sender, sender_id, content, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.formatted(MESSAGE_TABLE),
                sessionId, seq, draft.messageId(), draft.sender(),
                nullToEmpty(draft.senderId()), draft.content(), draft.timestampMillis());
        // 同步会话冗余列：message_count 与最近活跃时间（列表倒序排序键）
        long activeAt = draft.timestampMillis() > 0 ? draft.timestampMillis() : System.currentTimeMillis();
        jdbcTemplate.update(
                "UPDATE " + SESSION_TABLE + " SET message_count = ?, last_active_at_ms = ? WHERE session_id = ?",
                seq, activeAt, sessionId);
        return seq;
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        // 不带租户条件：调用方（服务层）自行做归属校验，越权统一按「不存在」对外返回
        return loadById(sessionId, null);
    }

    @Override
    public Optional<Session> findById(String sessionId, String tenantId) {
        return loadById(sessionId, tenantId);
    }

    /** 按会话 ID 加载聚合；{@code tenantId} 非空时强制租户条件，并回表加载消息序列。 */
    private Optional<Session> loadById(String sessionId, String tenantId) {
        List<Session> rows = tenantId == null
                ? jdbcTemplate.query("SELECT * FROM " + SESSION_TABLE + " WHERE session_id = ?",
                        SESSION_ROW_MAPPER, sessionId)
                : jdbcTemplate.query(
                        "SELECT * FROM " + SESSION_TABLE + " WHERE session_id = ? AND tenant_id = ?",
                        SESSION_ROW_MAPPER, sessionId, tenantId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Session row = rows.get(0);
        List<SessionMessage> messages = jdbcTemplate.query(
                "SELECT * FROM " + MESSAGE_TABLE + " WHERE session_id = ? ORDER BY seq ASC",
                MESSAGE_ROW_MAPPER, sessionId);
        // 已加载消息时以库内条数为准，用完整聚合重建（seq 游标自 max(seq)+1 续接）
        return Optional.of(Session.restore(row.getId(), row.getTenantId(), row.getUserId(),
                row.getChannel(), row.getState(), row.getAgentId(), row.getSummary(),
                row.getCreatedAt().toEpochMilli(), row.getUpdatedAt().toEpochMilli(),
                messages.size(), messages));
    }

    @Override
    public List<Session> listByStates(Set<State> states, String tenantId) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        // 客服队列按进入时间先后（FIFO）；列表不回表读消息，条数取冗余列
        String placeholders = states.stream().map(s -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        states.forEach(s -> args.add(s.name()));
        return jdbcTemplate.query(
                "SELECT * FROM " + SESSION_TABLE + " WHERE tenant_id = ? AND state IN (" + placeholders
                        + ") ORDER BY created_at_ms ASC",
                SESSION_ROW_MAPPER, args.toArray());
    }

    @Override
    public List<Session> listByAgent(String agentId, String tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM " + SESSION_TABLE
                        + " WHERE agent_id = ? AND tenant_id = ? ORDER BY last_active_at_ms DESC",
                SESSION_ROW_MAPPER, nullToEmpty(agentId), tenantId);
    }

    @Override
    public List<SessionOverview> listByUser(String userId, String tenantId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // last_preview 由相关子查询取该会话 seq 最大的一条（列表不回表读全部消息）
        return jdbcTemplate.query("""
                SELECT s.session_id, s.user_id, s.channel, s.state, s.created_at_ms,
                       s.last_active_at_ms, s.message_count,
                       COALESCE((SELECT m.content FROM %s m WHERE m.session_id = s.session_id
                                 ORDER BY m.seq DESC LIMIT 1), '') AS last_preview
                FROM %s s
                WHERE s.tenant_id = ? AND s.user_id = ?
                ORDER BY s.last_active_at_ms DESC
                LIMIT ?
                """.formatted(MESSAGE_TABLE, SESSION_TABLE),
                (rs, rowNum) -> new SessionOverview(
                        rs.getString("session_id"), rs.getString("user_id"), rs.getString("channel"),
                        State.valueOf(rs.getString("state")), rs.getLong("created_at_ms"),
                        rs.getLong("last_active_at_ms"), rs.getInt("message_count"),
                        rs.getString("last_preview")),
                tenantId, userId, limit);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
