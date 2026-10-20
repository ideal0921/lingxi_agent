package com.lingxi.start.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.common.trace.TraceContext;
import com.lingxi.knowledge.domain.event.KnowledgeEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 知识库入库事件 Kafka 生产者（M3-3.2）：把文档入库任务投递到 {@code kb.doc.ingest}，
 * 由 AI 域拉取原件、解析切分并写入向量库。
 *
 * <p>以 {@code doc_id} 作为消息键，保证同一文档的事件有序落同一分区（同文档版本
 * 更新不会乱序）。仅在 {@code lingxi.kafka.enabled=true} 时装配。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class KafkaKnowledgeEventPublisher implements KnowledgeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaKnowledgeEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String ingestTopic;
    private final String deleteTopic;

    public KafkaKnowledgeEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${lingxi.kafka.topic.kb-ingest:kb.doc.ingest}") String ingestTopic,
            @Value("${lingxi.kafka.topic.kb-delete:kb.doc.delete}") String deleteTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.ingestTopic = ingestTopic;
        this.deleteTopic = deleteTopic;
    }

    @Override
    public void publishIngest(String docId, String tenantId, String kbId, int version,
                              String fileName, String fileUrl) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trace_id", TraceContext.traceId());
        payload.put("doc_id", docId);
        payload.put("tenant_id", tenantId == null ? "" : tenantId);
        payload.put("kb_id", kbId);
        payload.put("version", version);
        payload.put("file_name", fileName);
        payload.put("file_url", fileUrl);
        send(ingestTopic, docId, payload, "入库事件");
    }

    @Override
    public void publishDelete(String docId, String tenantId, String kbId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trace_id", TraceContext.traceId());
        payload.put("doc_id", docId);
        payload.put("tenant_id", tenantId == null ? "" : tenantId);
        payload.put("kb_id", kbId);
        send(deleteTopic, docId, payload, "下线事件");
    }

    /** 统一的 JSON 序列化 + 文档键分区投递；序列化/投递失败记录后不抛出，避免拖垮业务接口。 */
    private void send(String topic, String docId, Map<String, Object> payload, String eventName) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化失败属编码缺陷：记录后不抛出，避免拖垮接口，状态/数据可由重试或重传补偿
            log.error("{}序列化失败 docId={}", eventName, docId, e);
            return;
        }

        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, docId, json);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("{}投递失败 docId={} topic={}", eventName, docId, topic, ex);
            } else {
                log.debug("{}已投递 docId={} partition={}", eventName, docId,
                        result.getRecordMetadata().partition());
            }
        });
    }
}
