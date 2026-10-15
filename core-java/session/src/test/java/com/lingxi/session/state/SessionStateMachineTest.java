package com.lingxi.session.state;

import com.lingxi.session.state.SessionStateMachine.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机单测：覆盖合法迁移、非法迁移与重复关闭被拒绝。
 */
class SessionStateMachineTest {

    @Test
    void 合法迁移_init到人工接管_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.INIT, State.HUMAN_CHAT));
        assertEquals(State.HUMAN_CHAT, SessionStateMachine.transition(State.INIT, State.HUMAN_CHAT));
    }

    @Test
    void 合法迁移_init直接关闭_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.INIT, State.CLOSED));
    }

    @Test
    void 合法迁移_人工会话到关闭_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.HUMAN_CHAT, State.CLOSED));
    }

    @Test
    void 非法迁移_已关闭再关闭_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(State.CLOSED, State.CLOSED));
        assertThrows(IllegalStateTransitionException.class,
                () -> SessionStateMachine.transition(State.CLOSED, State.CLOSED));
    }

    @Test
    void 非法迁移_人工会话回退到init_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(State.HUMAN_CHAT, State.INIT));
    }

    @Test
    void 非法迁移_已关闭改回人工_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(State.CLOSED, State.HUMAN_CHAT));
    }

    @Test
    void 非法迁移_空状态_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(null, State.CLOSED));
        assertFalse(SessionStateMachine.canTransition(State.INIT, null));
    }

    @Test
    void 合法迁移_init进入AI应答_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.INIT, State.AI_ACTIVE));
        assertEquals(State.AI_ACTIVE, SessionStateMachine.transition(State.INIT, State.AI_ACTIVE));
    }

    @Test
    void 合法迁移_AI会话被接管到人工_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.AI_ACTIVE, State.HUMAN_CHAT));
        assertEquals(State.HUMAN_CHAT, SessionStateMachine.transition(State.AI_ACTIVE, State.HUMAN_CHAT));
    }

    @Test
    void 合法迁移_AI会话关闭_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.AI_ACTIVE, State.CLOSED));
    }

    @Test
    void 非法迁移_AI会话回退到init_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(State.AI_ACTIVE, State.INIT));
    }

    @Test
    void 非法迁移_人工会话切回AI_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(State.HUMAN_CHAT, State.AI_ACTIVE));
    }

    @Test
    void 合法迁移_init转人工_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.INIT, State.WAITING_HUMAN));
        assertEquals(State.WAITING_HUMAN, SessionStateMachine.transition(State.INIT, State.WAITING_HUMAN));
    }

    @Test
    void 合法迁移_AI会话转人工_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.AI_ACTIVE, State.WAITING_HUMAN));
        assertEquals(State.WAITING_HUMAN, SessionStateMachine.transition(State.AI_ACTIVE, State.WAITING_HUMAN));
    }

    @Test
    void 合法迁移_待人工接入到坐席受理_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.WAITING_HUMAN, State.HUMAN_CHAT));
        assertEquals(State.HUMAN_CHAT, SessionStateMachine.transition(State.WAITING_HUMAN, State.HUMAN_CHAT));
    }

    @Test
    void 合法迁移_待人工接入直接关闭_应允许() {
        assertTrue(SessionStateMachine.canTransition(State.WAITING_HUMAN, State.CLOSED));
    }

    @Test
    void 非法迁移_待人工接入回退AI_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(State.WAITING_HUMAN, State.AI_ACTIVE));
        assertThrows(IllegalStateTransitionException.class,
                () -> SessionStateMachine.transition(State.WAITING_HUMAN, State.AI_ACTIVE));
    }

    @Test
    void 非法迁移_待人工接入回退init_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(State.WAITING_HUMAN, State.INIT));
    }

    @Test
    void 非法迁移_人工会话转回待人工_应被拒绝() {
        assertFalse(SessionStateMachine.canTransition(State.HUMAN_CHAT, State.WAITING_HUMAN));
    }
}
