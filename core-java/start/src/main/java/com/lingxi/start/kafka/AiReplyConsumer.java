package com.lingxi.start.kafka;

import java.time.Instant;

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
import com.lingxi.session.event.AnalyticsEventPublisher;
import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.trace.application.ApiTraceEntry;
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.domain.ApiTraceSpan;

/**
 * AI 最终回答消费者（M3-3.8）：把 AI 生成结果作为助手消息落库到会话。
 *
 * <p>落库后，断连续传（resume → PullMessages）与历史回源即可取到 AI 回答；
 * 消息 ID 沿用上游生成的 {@code ai-<uuid>}，前端据此与流式内容归并为同一条，
 * 避免打字机内容与落库消息重复渲染。</p>
 *
 * <p>幂等：重复投递同一 {@code message_id} 时跳过落库（Kafka 至少一次语义兜底）；
 * M5-5.1：**仅首次落库**时发布 {@code ai_replied} 分析埋点（避免重投导致漏斗虚高）。</p>
 *
 * <p>M5-5.9 链路：从消息体读取 {@code trace_id} 绑定上下文（缺字段则新起一条链路，不 panic），
 * 使本段消费挂到同一链路，并让落库过程中发布的分析埋点继续透传该 traceId；消费段记 KAFKA span。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.kafka.enabled", havingValue = "true")
public class AiReplyConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiReplyConsumer.class);

    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    /** 分析埋点发布端口（M5-5.1）。 */
    private final AnalyticsEventPublisher analyticsPublisher;
    /** 链路 span 采集端口（M5-5.9）。 */
    private final ApiTraceService apiTraceService;
    /** 本消费者订阅的主题（作为 span 的 entry 标识）。 */
    private final String topic;

    public AiReplyConsumer(SessionRepository sessionRepository, ObjectMapper objectMapper,
                           AnalyticsEventPublisher analyticsPublisher, ApiTraceService apiTraceService,
                           @Value("${lingxi.kafka.topic.ai-reply:lingxi.ai.reply}") String topic) {
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.analyticsPublisher = analyticsPublisher;
        this.apiTraceService = apiTraceService;
        this.topic = topic;
    }

    @KafkaListener(
            topics = "${lingxi.kafka.topic.ai-reply:lingxi.ai.reply}",
            groupId = "${lingxi.kafka.consumer-group:core-java}")
    public void onAiReply(String message) {
        long startedAt = System.currentTimeMillis();
        JsonNode node = parse(message);
        // 链路续接：缺 trace_id 字段时 TraceIds.normalize 会新生成，保证这段仍有归属链路（不 panic）
        String traceId = node == null ? TraceIds.generate() : TraceIds.normalize(node.path("trace_id").asText(""));
        String tenantId = node == null ? "" : node.path("tenant_id").asText("");
        String userId = node == null ? "" : node.path("user_id").asText("");
        TraceContext.set(traceId, TraceIds.newSpanId());

        boolean ok = false;
        String errorMsg = "";
        try {
            if (node == null) {
                errorMsg = "AI 回答事件解析失败";
                log.warn("忽略非法的 AI 回答事件（JSON 解析失败）: {}", message);
                return;
            }
            String sessionId = node.path("session_id").asText("");
            String messageId = node.path("message_id").asText("");
            String content = node.path("content").asText("");
            if (sessionId.isBlank() || content.isBlank()) {
                errorMsg = "缺少 session_id 或 content";
                log.warn("忽略非法的 AI 回答事件 sessionId={} messageId={}", sessionId, messageId);
                return;
            }
            sessionRepository.findById(sessionId).ifPresentOrElse(
                    session -> persist(session, sessionId, messageId, content),
                    () -> log.warn("会话不存在，忽略 AI 回答 sessionId={}", sessionId));
            ok = true;
        } catch (Exception e) {
            errorMsg = e.getMessage();
            log.error("处理 AI 回答事件失败: {}", message, e);
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

    /** 落库助手消息（含幂等校验）；返回是否**首次**落库（供埋点判定，防重投虚高）。 */
    private boolean persist(Session session, String sessionId, String messageId, String content) {
        if (session.hasMessageId(messageId)) {
            log.debug("AI 回答已落库，跳过重复事件 sessionId={} messageId={}", sessionId, messageId);
            return false;
        }
        // M5-5.7：助手消息交存储侧追加并取回**权威 seq**（与并发用户消息共享同一行锁分配，
        // 不再用聚合算出的 seq，否则同时到达的用户消息与 AI 回复会覆盖丢一条）
        int seq = sessionRepository.appendMessage(sessionId, new SessionMessage(
                messageId, sessionId, "agent", "ai", content, 0L, Instant.now()));
        log.info("AI 回答已落库 sessionId={} messageId={} seq={}", sessionId, messageId, seq);
        // M5-5.1 分析埋点：AI 应答（漏斗阶段，聚合侧取最早 = 首次应答）；链路随上下文继续透传
        analyticsPublisher.publishLifecycle(session.getId(), session.getTenantId(),
                AnalyticsEventPublisher.STAGE_AI_REPLIED, session.getChannel(),
                session.getUserId(), session.getAgentId(), messageId);
        return true;
    }
}
