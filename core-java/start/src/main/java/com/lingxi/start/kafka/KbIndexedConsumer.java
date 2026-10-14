package com.lingxi.start.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.knowledge.application.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 文档入库结果消费者（M3-3.2）：消费 {@code kb.doc.indexed}，把 AI 域的入库结果
 * （成功/失败、子块与父块数量）回写到文档版本状态与切片统计。
 *
 * <p>幂等/防过期：仅当事件的 {@code version} 与文档当前版本一致时才更新，
 * 避免旧版本迟到的事件覆盖新版本状态（版本判定在 {@link DocumentService} 内）。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class KbIndexedConsumer {

    private static final Logger log = LoggerFactory.getLogger(KbIndexedConsumer.class);

    private final DocumentService documentService;
    private final ObjectMapper objectMapper;

    public KbIndexedConsumer(DocumentService documentService, ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${lingxi.kafka.topic.kb-indexed:kb.doc.indexed}",
            groupId = "${lingxi.kafka.consumer-group:core-java}")
    public void onIndexed(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String docId = node.path("doc_id").asText("");
            String tenantId = node.path("tenant_id").asText("");
            String status = node.path("status").asText("");
            if (docId.isBlank() || status.isBlank()) {
                log.warn("忽略非法的入库结果事件: {}", message);
                return;
            }
            documentService.applyIndexedResult(
                    docId,
                    tenantId,
                    node.path("version").asInt(1),
                    status,
                    node.path("child_count").asInt(0),
                    node.path("parent_count").asInt(0),
                    node.path("message").asText(""));
        } catch (Exception e) {
            log.error("处理入库结果事件失败: {}", message, e);
        }
    }
}
