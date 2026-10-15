package com.lingxi.session.ai;

import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;

/**
 * 回复建议异步队列发布端口（依赖倒置，M4-4.6）。
 *
 * <p>同步通道降级（过载/超时/故障）时，由会话域把请求转入异步队列，稍后经坐席端推送结果。
 * 生产实现由 {@code lingxi-start} 以 Kafka 提供；未启用消息总线或关闭快捷回复时使用空实现。</p>
 */
public interface SuggestRequestPublisher {

    /**
     * 尝试把生成请求转入异步队列。
     *
     * @param request 与同步通道一致的上下文请求
     * @return 是否成功入队；false 时调用方需如实回传 {@code queued=false}，让前端提示重试
     */
    boolean enqueue(GenerateSuggestRequest request);
}
