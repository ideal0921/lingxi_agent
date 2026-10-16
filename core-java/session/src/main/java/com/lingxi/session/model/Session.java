package com.lingxi.session.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lingxi.session.state.IllegalStateTransitionException;
import com.lingxi.session.state.SessionStateMachine.State;

/**
 * 会话聚合实体：持有状态、参与者与消息序列。
 *
 * <p>消息 seq 从 1 递增，作为断连续传游标；所有变更通过
 * {@link SessionService#sendMessage} 等方法完成，保证 seq 单调。</p>
 */
public class Session {

    private final String id;
    private final String tenantId;
    private final String userId;
    private final String channel;
    private final Instant createdAt;

    private State state;
    private String agentId;
    /** 长程记忆摘要（M3-3.11）：超出上下文窗口的旧消息由 AI 域压缩后回写，随事件带入下一轮。 */
    private String summary = "";
    private Instant updatedAt;
    private long nextSeq = 1;
    private final List<SessionMessage> messages = new ArrayList<>();

    /** 摘要长度上限（字符），超出截断，避免异常大字段随事件传输。 */
    private static final int MAX_SUMMARY_LEN = 2000;

    /**
     * 已落库的消息条数（M5-5.7 持久化）：SQL 列表查询只取会话行、不回表读消息，
     * 故以该值兜底 {@link #messageCount()}，避免列表显示 0 条。
     */
    private int persistedMessageCount;

    /** 新建会话（id 由聚合生成，状态 INIT）。 */
    public Session(String tenantId, String userId, String channel) {
        this(UUID.randomUUID().toString(), tenantId, userId, channel, Instant.now());
        this.updatedAt = this.createdAt;
        this.state = State.INIT;
    }

    private Session(String id, String tenantId, String userId, String channel, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.channel = channel;
        this.createdAt = createdAt;
    }

    /**
     * 由存储层重建聚合（M5-5.7 持久化）：恢复状态、摘要、时间戳与消息序列。
     *
     * <p>列表类查询不传 {@code messages}（避免回表读全部消息），此时
     * {@code messageCount} 决定 {@link #messageCount()} 的返回值。</p>
     *
     * @param messages 有序消息（按 seq 升序）；不加载时为 null 或空列表
     */
    public static Session restore(String id, String tenantId, String userId, String channel,
                                  State state, String agentId, String summary,
                                  long createdAtMs, long updatedAtMs, int messageCount,
                                  List<SessionMessage> messages) {
        Session session = new Session(id, tenantId, userId, channel, Instant.ofEpochMilli(createdAtMs));
        session.state = state;
        session.agentId = agentId;
        session.summary = summary == null ? "" : summary;
        session.updatedAt = Instant.ofEpochMilli(updatedAtMs);
        session.persistedMessageCount = messageCount;
        if (messages != null && !messages.isEmpty()) {
            session.messages.addAll(messages);
            // seq 单调：下一序号取已存消息最大 seq + 1（新会话从 1 开始）
            session.nextSeq = messages.stream().mapToLong(SessionMessage::seq).max().orElse(0L) + 1;
        }
        return session;
    }

    public synchronized void changeState(State target) {
        this.state = com.lingxi.session.state.SessionStateMachine.transition(this.state, target);
        this.updatedAt = Instant.now();
    }

    /**
     * 客服接管：原子完成「状态校验 + 迁移 + 坐席绑定」。
     * 并发抢单场景下，同一会话仅一个坐席能成功（synchronized 保证原子性）。
     *
     * @param agentId 接管坐席 ID
     * @return 接管结果；ok=false 时 reason 非空
     */
    public synchronized TakeOverResult takeOver(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return new TakeOverResult(false, "agentId 不能为空");
        }
        try {
            this.state = com.lingxi.session.state.SessionStateMachine.transition(this.state, State.HUMAN_CHAT);
        } catch (IllegalStateTransitionException e) {
            return new TakeOverResult(false, e.getMessage());
        }
        this.agentId = agentId;
        this.updatedAt = Instant.now();
        return new TakeOverResult(true, null);
    }

    /**
     * 追加消息并返回新分配的 seq（线程安全，seq 单调递增）。
     */
    public synchronized SessionMessage appendMessage(String sender, String senderId, String content) {
        return appendMessage(sender, senderId, content, UUID.randomUUID().toString());
    }

    /**
     * 追加消息并使用**指定消息 ID**（AI 流式应答用它保持与上游片段一致的消息标识，
     * 前端据此把流式内容与落库消息归并为同一条，避免重复渲染）。
     */
    public synchronized SessionMessage appendMessage(String sender, String senderId, String content,
                                                     String messageId) {
        SessionMessage message = new SessionMessage(
                messageId, this.id, sender, senderId, content, nextSeq++, Instant.now());
        messages.add(message);
        this.updatedAt = message.timestampMillis() > 0
                ? Instant.ofEpochMilli(message.timestampMillis()) : Instant.now();
        return message;
    }

    /**
     * 是否已存在指定消息 ID（幂等消费：重复事件不重复落库）。
     */
    public synchronized boolean hasMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        return messages.stream().anyMatch(m -> messageId.equals(m.messageId()));
    }

    /**
     * 按游标拉取遗漏消息（lastSeq 之后），供断连续传。
     *
     * @param lastSeq 已消费的最大 seq，0 表示从头拉取
     * @param limit   单次上限
     */
    public synchronized List<SessionMessage> pullAfter(long lastSeq, int limit) {
        List<SessionMessage> result = new ArrayList<>();
        for (SessionMessage m : messages) {
            if (m.seq() > lastSeq) {
                result.add(m);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getChannel() {
        return channel;
    }

    public State getState() {
        return state;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
        this.updatedAt = Instant.now();
    }

    public String getAgentId() {
        return agentId;
    }

    /** 会话摘要（长程记忆），供 AI 域拼装上下文。 */
    public synchronized String getSummary() {
        return summary;
    }

    /**
     * 回写会话摘要（AI 域压缩旧消息后投递，幂等覆盖）。
     *
     * @param summary 新摘要；空值按空摘要处理，超长截断
     */
    public synchronized void updateSummary(String summary) {
        String value = summary == null ? "" : summary.trim();
        this.summary = value.length() > MAX_SUMMARY_LEN ? value.substring(0, MAX_SUMMARY_LEN) : value;
        this.updatedAt = Instant.now();
    }

    /**
     * 取最近 N 条消息（含顺序不变），用于拼装「近 N 轮」上下文。
     *
     * @param limit 条数上限；<=0 时返回空列表
     */
    public synchronized List<SessionMessage> recentMessages(int limit) {
        if (limit <= 0 || messages.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, messages.size() - limit);
        return List.copyOf(messages.subList(from, messages.size()));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public synchronized int messageCount() {
        // 已加载消息时以实际条数为准；列表轻量查询（未回表读消息）时用落库条数兜底
        return messages.isEmpty() ? persistedMessageCount : messages.size();
    }

    /**
     * 全量消息快照（落库/测试用），返回不可变副本，避免外部改动内部消息序列。
     */
    public synchronized List<SessionMessage> messageSnapshot() {
        return List.copyOf(messages);
    }

    /**
     * 接管结果：{@code ok=true} 表示接管成功；失败时 {@code reason} 说明原因。
     */
    public record TakeOverResult(boolean ok, String reason) {
    }
}
