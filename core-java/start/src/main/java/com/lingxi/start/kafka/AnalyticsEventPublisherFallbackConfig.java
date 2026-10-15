package com.lingxi.start.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lingxi.session.event.AnalyticsEventPublisher;
import com.lingxi.session.event.NoOpAnalyticsEventPublisher;

/**
 * 消息总线未启用时的兜底装配（M5-5.1）：提供分析埋点空实现，
 * 保证会话域可独立启动与单测（与 {@code SessionEventPublisherFallbackConfig} 同构）。
 */
@Configuration
public class AnalyticsEventPublisherFallbackConfig {

    @Bean
    @ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "false", matchIfMissing = true)
    public AnalyticsEventPublisher noOpAnalyticsEventPublisher() {
        return new NoOpAnalyticsEventPublisher();
    }
}
