package com.lingxi.session.state;

/**
 * 非法状态迁移异常：由状态机拒绝不合规迁移时抛出。
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(SessionStateMachine.State from,
                                           SessionStateMachine.State to) {
        super(String.format("非法状态迁移：%s -> %s", from, to));
    }
}
