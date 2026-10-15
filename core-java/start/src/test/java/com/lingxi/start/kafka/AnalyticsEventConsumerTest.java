package com.lingxi.start.kafka;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.report.domain.AnalyticsEvent;
import com.lingxi.report.domain.AnalyticsEventType;
import com.lingxi.report.domain.repo.AnalyticsEventRepository;
import com.lingxi.report.domain.repo.ReportRepository;
import com.lingxi.report.domain.FeedbackItem;
import com.lingxi.report.domain.FunnelStage;
import com.lingxi.report.domain.TokenUsageRow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分析事件消费者测试（M5-5.1，离线：直接调监听方法，不起 Kafka）。
 *
 * <p>覆盖：按 type 分派到报表域、非法事件（缺 event_id / 未知 type / 损坏 JSON）忽略、
 * 异常不外抛（单条失败不影响消费循环）。</p>
 */
class AnalyticsEventConsumerTest {

    private RecordingEventRepository eventRepository;
    private RecordingReportRepository reportRepository;
    private AnalyticsEventConsumer consumer;

    @BeforeEach
    void setUp() {
        eventRepository = new RecordingEventRepository();
        reportRepository = new RecordingReportRepository();
        var ingestService = new com.lingxi.report.application.ReportIngestService(
                eventRepository, reportRepository, new ObjectMapper());
        consumer = new AnalyticsEventConsumer(ingestService, new ObjectMapper());
    }

    @Test
    void dispatchesLifecycleEventToFunnel() {
        consumer.onAnalyticsEvent("""
                {"event_id":"e1","type":"session_lifecycle","tenant_id":"t1","session_id":"s1",
                 "occurred_at":1700000000000,"stage":"created","channel":"web"}
                """);

        assertEquals(List.of(FunnelStage.CREATED), reportRepository.stages);
        assertEquals(1, eventRepository.events.size());
        assertEquals("t1", eventRepository.events.get(0).tenantId());
        assertEquals("s1", eventRepository.events.get(0).sessionId());
        assertEquals(1700000000000L, eventRepository.events.get(0).occurredAt());
    }

    @Test
    void dispatchesUsageEventToTokenAggregation() {
        consumer.onAnalyticsEvent("""
                {"event_id":"e2","type":"llm_usage","tenant_id":"t1","session_id":"s1",
                 "occurred_at":1700000000000,"provider":"deepseek","model":"deepseek-chat",
                 "prompt_tokens":10,"completion_tokens":5,"estimated":false,"first_token_ms":500}
                """);

        assertTrue(reportRepository.tokenAccumulated);
        assertEquals("deepseek", reportRepository.provider);
    }

    @Test
    void dispatchesFeedbackEventToFeedbackStore() {
        consumer.onAnalyticsEvent("""
                {"event_id":"e3","type":"session_feedback","tenant_id":"t1","session_id":"s1",
                 "occurred_at":1700000000000,"rating":4,"comment":"不错","user_id":"u1"}
                """);

        assertEquals(1, reportRepository.feedbackCount);
    }

    @Test
    void ignoresIllegalEvents() {
        consumer.onAnalyticsEvent("{\"type\":\"session_lifecycle\"}");           // 缺 event_id
        consumer.onAnalyticsEvent("{\"event_id\":\"x\",\"type\":\"unknown\"}"); // 未知 type
        consumer.onAnalyticsEvent("{not json");                                  // 损坏 JSON

        assertTrue(eventRepository.events.isEmpty(), "非法事件不应落明细");
        assertTrue(reportRepository.stages.isEmpty());
    }

    @Test
    void toleratesMissingOccurredAt() {
        consumer.onAnalyticsEvent("""
                {"event_id":"e4","type":"session_lifecycle","tenant_id":"t1","session_id":"s1",
                 "stage":"closed"}
                """);

        // 缺少发生时间时回退为当前时间，事件仍应正常落库
        assertEquals(1, reportRepository.stages.size());
        assertTrue(eventRepository.events.get(0).occurredAt() > 0);
    }

    // ---------- 内存替身 ----------

    private static final class RecordingEventRepository implements AnalyticsEventRepository {

        private final List<AnalyticsEvent> events = new ArrayList<>();

        @Override
        public boolean insertIgnore(AnalyticsEvent event) {
            return events.add(event);
        }
    }

    private static final class RecordingReportRepository implements ReportRepository {

        private final List<FunnelStage> stages = new ArrayList<>();
        private boolean tokenAccumulated;
        private String provider = "";
        private int feedbackCount;

        @Override
        public void recordFunnelStage(String sessionId, String tenantId, String channel,
                                      FunnelStage stage, long occurredAtMs) {
            stages.add(stage);
        }

        @Override
        public void accumulateTokenUsage(String statDate, String tenantId, String provider,
                                         String model, long promptTokens, long completionTokens,
                                         boolean estimated, long firstTokenMs) {
            tokenAccumulated = true;
            this.provider = provider;
        }

        @Override
        public void saveFeedback(String feedbackId, String sessionId, String tenantId, String userId,
                                 int rating, String comment, String tags, long createdAtMs) {
            feedbackCount++;
        }

        @Override
        public java.util.Map<FunnelStage, Integer> countFunnelStages(String tenantId, long fromMs, long toMs) {
            return java.util.Map.of();
        }

        @Override
        public List<TokenUsageRow> queryTokenUsage(String tenantId, String fromDate, String toDate) {
            return List.of();
        }

        @Override
        public List<FeedbackItem> queryRecentFeedback(String tenantId, long fromMs, long toMs, int limit) {
            return List.of();
        }

        @Override
        public java.util.Map<Integer, Integer> queryRatingDistribution(String tenantId, long fromMs, long toMs) {
            return java.util.Map.of();
        }
    }
}
