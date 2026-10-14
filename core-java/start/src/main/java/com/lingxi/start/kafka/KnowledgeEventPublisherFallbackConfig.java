package com.lingxi.start.kafka;

import com.lingxi.knowledge.domain.event.KnowledgeEventPublisher;
import com.lingxi.knowledge.domain.event.NoOpKnowledgeEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息总线未启用时的兜底装配：提供知识库事件发布空实现，保证知识域可独立启动与单测。
 *
 * <p>用 {@code havingValue="false", matchIfMissing=true} 显式取反，与会话域的
 * 兜底装配保持一致（避免依赖 {@code @ConditionalOnMissingBean} 的注册顺序不确定性）。</p>
 */
@Configuration
public class KnowledgeEventPublisherFallbackConfig {

    @Bean
    @ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "false", matchIfMissing = true)
    public KnowledgeEventPublisher noOpKnowledgeEventPublisher() {
        return new NoOpKnowledgeEventPublisher();
    }
}
