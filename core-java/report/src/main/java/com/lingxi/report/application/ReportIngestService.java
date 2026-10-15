package com.lingxi.report.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.report.domain.AnalyticsEvent;
import com.lingxi.report.domain.FunnelStage;
import com.lingxi.report.domain.repo.AnalyticsEventRepository;
import com.lingxi.report.domain.repo.ReportRepository;

/**
 * 分析事件落库编排（M5-5.1）。
 *
 * <p><b>幂等优先</b>：先向明细表做幂等插入；仅当本次为**新事件**时才更新聚合表——
 * Kafka 至少一次语义下重投不会让漏斗/Token/满意度数值虚高。</p>
 *
 * <p>事件体字段按类型解析（{@code payload_json} 原文），新增字段无需改领域模型；
 * 任一事件解析失败只告警并跳过，不影响后续事件。</p>
 */
@Service
public class ReportIngestService {

    private static final Logger log = LoggerFactory.getLogger(ReportIngestService.class);

    /** 统计日时区（与 Java 侧 schema/报表口径一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 评分合法区间（与会话域提交校验一致）。 */
    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    private final AnalyticsEventRepository eventRepository;
    private final ReportRepository reportRepository;
    private final ObjectMapper objectMapper;

    public ReportIngestService(AnalyticsEventRepository eventRepository,
                               ReportRepository reportRepository,
                               ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理一条分析事件。
     *
     * @return {@code true} 表示本次为新事件并已更新聚合；{@code false} 表示重复投递（已跳过）
     */
    public boolean handle(AnalyticsEvent event) {
        if (!eventRepository.insertIgnore(event)) {
            log.debug("分析事件重复投递，跳过聚合 eventId={} type={}", event.eventId(), event.type());
            return false;
        }
        switch (event.type()) {
            case SESSION_LIFECYCLE -> ingestLifecycle(event);
            case LLM_USAGE -> ingestUsage(event);
            case SESSION_FEEDBACK -> ingestFeedback(event);
        }
        return true;
    }

    /** 会话生命周期 → 漏斗阶段时间戳（同阶段取最早）。 */
    private void ingestLifecycle(AnalyticsEvent event) {
        JsonNode payload = readPayload(event);
        FunnelStage stage = FunnelStage.fromWire(payload.path("stage").asText(""));
        if (stage == null) {
            log.warn("忽略未知漏斗阶段 eventId={} stage={}", event.eventId(),
                    payload.path("stage").asText(""));
            return;
        }
        reportRepository.recordFunnelStage(event.sessionId(), event.tenantId(),
                payload.path("channel").asText(""), stage, event.occurredAt());
        log.debug("漏斗阶段已记录 sessionId={} stage={}", event.sessionId(), stage.wire());
    }

    /** LLM 用量 → 日 × 租户 × 提供方 × 模型 累加。 */
    private void ingestUsage(AnalyticsEvent event) {
        JsonNode payload = readPayload(event);
        reportRepository.accumulateTokenUsage(
                statDate(event.occurredAt()),
                event.tenantId(),
                payload.path("provider").asText(""),
                payload.path("model").asText(""),
                payload.path("prompt_tokens").asLong(0),
                payload.path("completion_tokens").asLong(0),
                payload.path("estimated").asBoolean(false),
                payload.path("first_token_ms").asLong(0));
        log.debug("Token 用量已累加 sessionId={} provider={} estimated={}", event.sessionId(),
                payload.path("provider").asText(""), payload.path("estimated").asBoolean(false));
    }

    /** 满意度 → 会话维度保存（重复提交覆盖）。 */
    private void ingestFeedback(AnalyticsEvent event) {
        JsonNode payload = readPayload(event);
        int rating = payload.path("rating").asInt(0);
        if (rating < MIN_RATING || rating > MAX_RATING) {
            log.warn("忽略非法评分 eventId={} rating={}", event.eventId(), rating);
            return;
        }
        reportRepository.saveFeedback(UUID.randomUUID().toString(), event.sessionId(), event.tenantId(),
                payload.path("user_id").asText(""), rating, payload.path("comment").asText(""),
                payload.path("tags").asText(""), event.occurredAt());
        log.debug("满意度已落库 sessionId={} rating={}", event.sessionId(), rating);
    }

    /** 解析事件体原文；损坏时返回空节点（后续路径读取默认值并跳过）。 */
    private JsonNode readPayload(AnalyticsEvent event) {
        try {
            return objectMapper.readTree(event.payloadJson());
        } catch (Exception e) {
            log.warn("分析事件体解析失败 eventId={} type={}", event.eventId(), event.type(), e);
            return objectMapper.createObjectNode();
        }
    }

    /** epoch ms → 统计日（Asia/Shanghai）。 */
    private static String statDate(long occurredAtMs) {
        return Instant.ofEpochMilli(occurredAtMs).atZone(ZONE).toLocalDate().toString();
    }
}
