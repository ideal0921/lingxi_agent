package com.lingxi.session.event;

/**
 * 分析事件发布端口（依赖倒置，M5-5.1）。
 *
 * <p>会话域只声明「需要把生命周期与满意度埋点投递出去」，不关心中间件；
 * 生产实现由 {@code lingxi-start} 以 Kafka（{@code lingxi.analytics.event}）提供，
 * 未启用消息总线时使用空实现，保证会话域可独立启动与单测。</p>
 *
 * <p><b>注意</b>：会话域是内存实现，分析事件是会话漏斗的**唯一事实源**，因此事件体必须
 * 自带租户/渠道等上下文，消费侧不得回头查询会话域。</p>
 */
public interface AnalyticsEventPublisher {

    /** 建会话。 */
    String STAGE_CREATED = "created";
    /** 用户消息（每条都发；聚合侧取最早 = 首条用户消息）。 */
    String STAGE_USER_MSG = "user_msg";
    /** AI 回答落库（聚合侧取最早 = 首次 AI 应答）。 */
    String STAGE_AI_REPLIED = "ai_replied";
    /** 转人工 / 坐席接管（聚合侧取最早）。 */
    String STAGE_HANDOFF = "handoff";
    /** 会话关闭。 */
    String STAGE_CLOSED = "closed";

    /**
     * 发布会话生命周期埋点。
     *
     * @param sessionId 会话 ID
     * @param tenantId  租户 ID
     * @param stage     阶段（取本接口的 {@code STAGE_*} 常量）
     * @param channel   接入渠道（建会话时已知；其余阶段可为空串）
     * @param userId    用户 ID（可为空）
     * @param agentId   坐席 ID（转人工/接管时有值）
     * @param messageId 关联消息 ID（消息类埋点有值）
     */
    void publishLifecycle(String sessionId, String tenantId, String stage, String channel,
                          String userId, String agentId, String messageId);

    /**
     * 发布满意度评价埋点。
     *
     * @param sessionId 会话 ID
     * @param tenantId  租户 ID（由会话域按会话解析，前端不传）
     * @param userId    用户 ID
     * @param rating    评分 1~5
     * @param comment   评价内容（可空）
     * @param tags      标签（逗号分隔，可空）
     */
    void publishFeedback(String sessionId, String tenantId, String userId, int rating,
                         String comment, String tags);
}
