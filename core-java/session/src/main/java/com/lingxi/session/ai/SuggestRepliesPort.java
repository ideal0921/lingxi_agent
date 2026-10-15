package com.lingxi.session.ai;

import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;

/**
 * 回复建议同步端口（依赖倒置，M4-4.6）。
 *
 * <p>会话域只声明「同步请求 AI 生成回复建议草稿」，不关心具体传输方式。
 * 生产实现由 {@code lingxi-start} 以 gRPC 同步调用 AI 域（Python）；
 * 关闭快捷回复或未启用时使用空实现，保证会话域可独立启动与离线单测。</p>
 */
public interface SuggestRepliesPort {

    /**
     * 同步请求生成回复建议。
     *
     * @param request 已由会话域组装好的上下文请求
     * @return 生成结果；过载/超时等降级情况以 {@link SuggestResult#degraded} 表达，不抛异常
     */
    SuggestResult ask(GenerateSuggestRequest request);
}
