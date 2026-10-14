package com.lingxi.start.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lingxi.session.event.NoOpSessionEventPublisher;
import com.lingxi.session.event.SessionEventPublisher;

/**
 * 消息总线未启用时的兜底装配：提供空实现，保证会话域可独立启动与单测。
 *
 * <p>用 {@code havingValue="false", matchIfMissing=true} 显式取反，避免依赖
 * {@code @ConditionalOnMissingBean} 的注册顺序不确定性。</p>
 */
@Configuration
public class SessionEventPublisherFallbackConfig {

    @Bean
    @ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "false", matchIfMissing = true)
    public SessionEventPublisher noOpSessionEventPublisher() {
        return new NoOpSessionEventPublisher();
    }
}
