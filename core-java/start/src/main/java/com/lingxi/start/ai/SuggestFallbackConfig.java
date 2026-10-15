package com.lingxi.start.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.lingxi.session.ai.NoOpSuggestRepliesPort;
import com.lingxi.session.ai.NoOpSuggestRequestPublisher;
import com.lingxi.session.ai.SuggestRepliesPort;
import com.lingxi.session.ai.SuggestRequestPublisher;

/**
 * 快捷回复兜底装配（M4-4.6）：关闭同步通道或消息总线时提供空实现，
 * 保证会话域可独立启动与离线单测。
 *
 * <p>与会话/知识域事件兜底一致，统一用 {@code matchIfMissing} 显式取反，
 * 避免依赖 {@code @ConditionalOnMissingBean} 的注册顺序不确定性。</p>
 */
@Configuration
public class SuggestFallbackConfig {

    /** 快捷回复关闭：同步端口恒降级（{@code UNAVAILABLE}），不建连、不发起同步调用。 */
    @Bean
    @ConditionalOnProperty(name = "lingxi.suggest.enabled", havingValue = "false", matchIfMissing = false)
    public SuggestRepliesPort noOpSuggestRepliesPort() {
        return new NoOpSuggestRepliesPort();
    }

    /** 消息总线关闭：异步队列为空实现，入队恒失败，调用方如实回传 {@code queued=false}。 */
    @Bean
    @ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "false", matchIfMissing = true)
    public SuggestRequestPublisher noOpSuggestRequestPublisher() {
        return new NoOpSuggestRequestPublisher();
    }

    /**
     * 快捷回复关闭但消息总线开启时，{@link KafkaSuggestRequestPublisher} 仍会被装配
     * （其条件仅受 {@code lingxi.kafka.enabled} 约束）。按功能开关语义，快捷回复关闭时应
     * 彻底不投递，故用 {@code @Primary} 指定空实现优先：既消除两个同类型 Publisher 的装配歧义，
     * 又保证不向异步队列谎报入队。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "lingxi.suggest.enabled", havingValue = "false", matchIfMissing = false)
    public SuggestRequestPublisher disabledSuggestRequestPublisher() {
        return new NoOpSuggestRequestPublisher();
    }
}
