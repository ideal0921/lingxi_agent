package com.lingxi.session.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;

/**
 * 回复建议同步端口空实现：快捷回复关闭时使用，恒返回降级（{@code UNAVAILABLE}），不建连。
 */
public class NoOpSuggestRepliesPort implements SuggestRepliesPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpSuggestRepliesPort.class);

    @Override
    public SuggestResult ask(GenerateSuggestRequest request) {
        log.debug("快捷回复未启用，跳过同步生成 sessionId={}", request.getSessionId());
        return SuggestResult.degraded("UNAVAILABLE", 0);
    }
}
