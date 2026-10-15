package com.lingxi.session.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;

/**
 * 回复建议异步队列发布空实现：消息总线或快捷回复未启用时使用，恒返回入队失败，
 * 让调用方如实回传 {@code queued=false}（不谎报已排队）。
 */
public class NoOpSuggestRequestPublisher implements SuggestRequestPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpSuggestRequestPublisher.class);

    @Override
    public boolean enqueue(GenerateSuggestRequest request) {
        log.debug("消息总线/快捷回复未启用，跳过建议请求入队 sessionId={}", request.getSessionId());
        return false;
    }
}
