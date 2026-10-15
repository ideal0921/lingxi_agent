package com.lingxi.session.state;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 会话状态机（M2 核心，M3-3.11 AI 化）：
 * 状态与迁移约束集中于此，保证「非法迁移与重复关闭被拒绝」。
 *
 * <p>合法迁移（M4-4.5 转人工联动后）：</p>
 * <ul>
 *   <li>INIT → AI_ACTIVE（用户发消息，AI 应答开始）</li>
 *   <li>INIT → WAITING_HUMAN（AI 请求转人工）</li>
 *   <li>INIT → HUMAN_CHAT（坐席直接接管）</li>
 *   <li>INIT → CLOSED（用户直接关闭）</li>
 *   <li>AI_ACTIVE → WAITING_HUMAN（AI 请求转人工，退出 AI）</li>
 *   <li>AI_ACTIVE → HUMAN_CHAT（坐席接管）</li>
 *   <li>AI_ACTIVE → CLOSED（会话关闭）</li>
 *   <li>WAITING_HUMAN → HUMAN_CHAT（坐席接入）</li>
 *   <li>WAITING_HUMAN → CLOSED（会话关闭）</li>
 *   <li>HUMAN_CHAT → CLOSED（坐席关闭）</li>
 * </ul>
 *
 * <p>非法：CLOSED → 任意、HUMAN_CHAT → INIT/AI_ACTIVE/WAITING_HUMAN、
 * WAITING_HUMAN → INIT/AI_ACTIVE、AI_ACTIVE → INIT 等。</p>
 */
public final class SessionStateMachine {

    /** 会话状态（与 proto 枚举 SessionState 对齐）。 */
    public enum State {
        INIT, AI_ACTIVE, WAITING_HUMAN, HUMAN_CHAT, CLOSED
    }

    /** 迁移表：源状态 → 允许的目标状态集合。 */
    private static final Map<State, Set<State>> TRANSITIONS = Map.of(
            State.INIT, EnumSet.of(State.AI_ACTIVE, State.WAITING_HUMAN, State.HUMAN_CHAT, State.CLOSED),
            State.AI_ACTIVE, EnumSet.of(State.WAITING_HUMAN, State.HUMAN_CHAT, State.CLOSED),
            State.WAITING_HUMAN, EnumSet.of(State.HUMAN_CHAT, State.CLOSED),
            State.HUMAN_CHAT, EnumSet.of(State.CLOSED),
            State.CLOSED, EnumSet.noneOf(State.class)
    );

    private SessionStateMachine() {
        // 工具类，禁止实例化
    }

    /**
     * 校验迁移是否合法。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 合法返回 true；非法（含重复关闭）返回 false
     */
    public static boolean canTransition(State from, State to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(State.class)).contains(to);
    }

    /**
     * 执行迁移；非法迁移抛 {@link IllegalStateTransitionException}。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 迁移后的新状态
     */
    public static State transition(State from, State to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
        return to;
    }
}
