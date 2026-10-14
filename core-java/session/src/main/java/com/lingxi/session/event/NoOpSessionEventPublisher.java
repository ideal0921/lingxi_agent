package com.lingxi.session.event;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lingxi.session.model.SessionMessage;

/**
 * 会话事件发布空实现：未启用消息总线时使用，仅记录日志，保证会话域可独立启动。
 */
public class NoOpSessionEventPublisher implements SessionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpSessionEventPublisher.class);

    @Override
    public void publishUserMessage(String sessionId, String tenantId, String messageId, long seq, String content,
                                   String summary, List<SessionMessage> history) {
        log.debug("消息总线未启用，跳过用户消息事件发布 sessionId={} seq={} 历史条数={}",
                sessionId, seq, history == null ? 0 : history.size());
    }
}
