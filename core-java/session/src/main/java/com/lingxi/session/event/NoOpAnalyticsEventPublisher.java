package com.lingxi.session.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分析事件发布空实现（M5-5.1）：未启用消息总线时使用，仅记录日志，保证会话域可独立启动。
 */
public class NoOpAnalyticsEventPublisher implements AnalyticsEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpAnalyticsEventPublisher.class);

    @Override
    public void publishLifecycle(String sessionId, String tenantId, String stage, String channel,
                                 String userId, String agentId, String messageId) {
        log.debug("消息总线未启用，跳过生命周期埋点 sessionId={} stage={}", sessionId, stage);
    }

    @Override
    public void publishFeedback(String sessionId, String tenantId, String userId, int rating,
                               String comment, String tags) {
        log.debug("消息总线未启用，跳过满意度埋点 sessionId={} rating={}", sessionId, rating);
    }
}
