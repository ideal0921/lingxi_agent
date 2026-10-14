package com.lingxi.session.event;

import java.util.List;

import com.lingxi.session.model.SessionMessage;

/**
 * 会话事件发布端口（依赖倒置）。
 *
 * <p>会话域只声明「需要把用户消息广播出去」，不关心具体中间件。生产实现由
 * {@code lingxi-start} 模块以 Kafka 提供；未启用消息总线时使用空实现，
 * 保证会话域可独立启动与单测。</p>
 */
public interface SessionEventPublisher {

    /**
     * 发布用户消息事件（触发 AI 流式应答），并携带上下文窗口信息（M3-3.11）。
     *
     * @param sessionId 会话 ID
     * @param tenantId  租户 ID（AI 域据此隔离知识库）
     * @param messageId 用户消息 ID
     * @param seq       会话内游标
     * @param content   消息内容
     * @param summary   会话长程摘要（超出窗口的旧消息压缩结果，可为空）
     * @param history   近 N 轮对话（按时间升序，不含本条；可为空）
     */
    void publishUserMessage(String sessionId, String tenantId, String messageId, long seq, String content,
                            String summary, List<SessionMessage> history);
}
