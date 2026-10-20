package com.lingxi.start.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.common.trace.TraceContext;
import com.lingxi.common.trace.TraceIds;
import com.lingxi.report.application.ReportIngestService;
import com.lingxi.report.domain.AnalyticsEvent;
import com.lingxi.report.domain.AnalyticsEventType;
import com.lingxi.trace.application.ApiTraceEntry;
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.domain.ApiTraceSpan;

/**
 * 分析事件消费者（M5-5.1）：消费 {@code lingxi.analytics.event}，
 * 交给报表域幂等落库（明细 + 聚合）。
 *
 * <p>放在 {@code start} 模块是因为 Kafka 依赖在 start；异常一律隔离（单条失败不中断消费），
 * 且**幂等由明细表主键保证**——重投同一 {@code event_id} 时聚合不会重复累加。</p>
 *
 * <p>M5-5.9 链路：消费 {@code lingxi.analytics.event}（v1 覆盖主题）时读取消息体 {@code trace_id}
 * 绑定上下文并记 KAFKA span，使该异步 hop 在同一链路中可见（缺字段则新起链路，不 panic）。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class AnalyticsEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventConsumer.class);

    private final ReportIngestService ingestService;
    private final ObjectMapper objectMapper;
    /** 链路 span 采集端口（M5-5.9）。 */
    private final ApiTraceService apiTraceService;
    /** 本消费者订阅的主题（作为 span 的 entry 标识）。 */
    private final String topic;

    public AnalyticsEventConsumer(ReportIngestService ingestService, ObjectMapper objectMapper,
                                  ApiTraceService apiTraceService,
                                  @Value("${lingxi.kafka.topic.analytics-event:lingxi.analytics.event}") String topic) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
        this.apiTraceService = apiTraceService;
        this.topic = topic;
    }

    @KafkaListener(
            topics = "${lingxi.kafka.topic.analytics-event:lingxi.analytics.event}",
            groupId = "${lingxi.kafka.consumer-group:core-java}")
    public void onAnalyticsEvent(String message) {
        long startedAt = System.currentTimeMillis();
        JsonNode node = parse(message);
        // 链路续接：缺 trace_id 字段时生成新链路（保证这段仍有归属，且不 panic）
        String traceId = node == null ? TraceIds.generate() : TraceIds.normalize(node.path("trace_id").asText(""));
        String tenantId = node == null ? "" : node.path("tenant_id").asText("");
        String userId = node == null ? "" : node.path("user_id").asText("");
        TraceContext.set(traceId, TraceIds.newSpanId());

        boolean ok = false;
        String errorMsg = "";
        try {
            if (node == null) {
                errorMsg = "分析事件解析失败";
                log.warn("忽略非法的分析事件（解析失败）: {}", message);
                return;
            }
            String eventId = node.path("event_id").asText("");
            AnalyticsEventType type = AnalyticsEventType.fromWire(node.path("type").asText(""));
            if (eventId.isBlank() || type == null) {
                errorMsg = "缺少 event_id 或未知 type";
                log.warn("忽略非法的分析事件（缺 event_id 或未知 type）: {}", message);
                return;
            }
            ingestService.handle(new AnalyticsEvent(
                    eventId,
                    type,
                    tenantId,
                    node.path("session_id").asText(""),
                    node.path("occurred_at").asLong(System.currentTimeMillis()),
                    message));
            ok = true;
        } catch (Exception e) {
            errorMsg = e.getMessage();
            log.error("处理分析事件失败: {}", message, e);
        } finally {
            // 旁路记 span；必须先于清理上下文，才能取到刚绑定的 trace/span
            recordSpan(startedAt, ok, tenantId, userId, errorMsg);
            TraceContext.clear();
        }
    }

    /** 解析消息 JSON；失败返回 null（由调用方转为 ERROR span，不抛出）。 */
    private JsonNode parse(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (Exception e) {
            return null;
        }
    }

    /** 记录本段 Kafka 消费 span（服务固定 core-java，组件 KAFKA，入口为主题）。 */
    private void recordSpan(long startedAt, boolean ok, String tenantId, String userId, String errorMsg) {
        apiTraceService.record(ApiTraceEntry.ofServer(TraceContext.traceId(), TraceContext.spanId(),
                ApiTraceSpan.COMPONENT_KAFKA, topic,
                ok ? ApiTraceSpan.STATUS_OK : ApiTraceSpan.STATUS_ERROR, 0,
                tenantId, userId, "", System.currentTimeMillis() - startedAt, errorMsg, startedAt));
    }
}
