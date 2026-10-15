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
     * @param userId    用户 ID（AI 域据此隔离跨会话的用户级长期记忆，可为空）
     * @param messageId 用户消息 ID
     * @param seq       会话内游标
     * @param content   消息内容
     * @param summary   会话长程摘要（超出窗口的旧消息压缩结果，可为空）
     * @param history   近 N 轮对话（按时间升序，不含本条；可为空）
     */
    void publishUserMessage(String sessionId, String tenantId, String userId, String messageId, long seq,
                            String content, String summary, List<SessionMessage> history);

    /**
     * 发布「人工接管/转人工」事件（M4-4.5）：坐席接管或转人工成功时通知 AI 域
     * 立即终止该会话正在生成的回答。
     *
     * @param sessionId 会话 ID
     * @param reason    触发原因：{@code agent_takeover}（坐席接管）、
     *                  {@code handoff_requested}（AI 请求转人工）、
     *                  {@code state_change}（状态迁移至人工）
     * @param agentId   接管坐席 ID（无坐席时传空串）
     */
    void publishTakeover(String sessionId, String reason, String agentId);
}
