package com.lingxi.knowledge.domain.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息总线未启用时的空实现：记录调试日志，保证知识库域可独立启动与单测。
 */
public class NoOpKnowledgeEventPublisher implements KnowledgeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpKnowledgeEventPublisher.class);

    @Override
    public void publishIngest(String docId, String tenantId, String kbId, int version,
                              String fileName, String fileUrl) {
        log.debug("消息总线未启用，跳过入库事件投递 docId={} kbId={}", docId, kbId);
    }

    @Override
    public void publishDelete(String docId, String tenantId, String kbId) {
        log.debug("消息总线未启用，跳过下线事件投递 docId={} kbId={}", docId, kbId);
    }
}
