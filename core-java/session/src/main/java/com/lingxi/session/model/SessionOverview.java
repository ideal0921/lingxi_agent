package com.lingxi.session.model;

import com.lingxi.session.state.SessionStateMachine.State;

/**
 * 会话列表项（M5-5.7 历史会话列表）：只含列表展示所需的轻量字段，**不回表读消息体**，
 * 故 {@code lastPreview} 为最后一条消息原文（截断由服务层按展示长度完成）。
 *
 * @param sessionId      会话 ID
 * @param userId         会话归属用户
 * @param channel        接入渠道
 * @param state          当前状态
 * @param createdAtMs    建会话时间（epoch ms）
 * @param lastActiveAtMs 最近活跃时间（epoch ms，列表倒序排序键）
 * @param messageCount   消息条数
 * @param lastPreview    最后一条消息内容（无消息为空串）
 */
public record SessionOverview(
        String sessionId,
        String userId,
        String channel,
        State state,
        long createdAtMs,
        long lastActiveAtMs,
        int messageCount,
        String lastPreview) {
}
