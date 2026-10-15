package com.lingxi.report.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.report.domain.AnalyticsEvent;
import com.lingxi.report.domain.AnalyticsEventType;
import com.lingxi.report.domain.FunnelStage;
import com.lingxi.report.domain.repo.AnalyticsEventRepository;
import com.lingxi.report.domain.repo.ReportRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分析事件落库编排测试（M5-5.1，全内存替身，不连 MySQL）。
 *
 * <p>覆盖：幂等（重复投递不更新聚合）、生命周期阶段落库、未知阶段/非法评分忽略、
 * 事件体损坏降级、统计日按 Asia/Shanghai 归集。</p>
 */
class ReportIngestServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long OCCURRED_AT = 1_700_000_000_000L;

    private InMemoryEventRepository eventRepository;
    private InMemoryReportRepository reportRepository;
    private ReportIngestService service;

    @BeforeEach
    void setUp() {
        eventRepository = new InMemoryEventRepository();
        reportRepository = new InMemoryReportRepository();
        service = new ReportIngestService(eventRepository, reportRepository, new ObjectMapper());
    }

    private static AnalyticsEvent event(String eventId, AnalyticsEventType type, String payload) {
        return new AnalyticsEvent(eventId, type, "tenant-1", "s-1", OCCURRED_AT, payload);
    }

    @Test
    void lifecycleEventRecordsFunnelStage() {
        boolean handled = service.handle(event("e1", AnalyticsEventType.SESSION_LIFECYCLE,
                "{\"stage\":\"created\",\"channel\":\"web\"}"));

        assertTrue(handled);
        assertEquals(List.of(FunnelStage.CREATED), reportRepository.stages);
        assertEquals("web", reportRepository.lastChannel);
    }

    @Test
    void duplicateEventIsIgnoredAndDoesNotTouchAggregation() {
        AnalyticsEvent duplicated = event("e-dup", AnalyticsEventType.SESSION_LIFECYCLE,
                "{\"stage\":\"created\"}");

        assertTrue(service.handle(duplicated), "首次投递应为新事件");
        assertFalse(service.handle(duplicated), "重投同一 eventId 应被忽略");
        assertEquals(1, reportRepository.stages.size(), "聚合不应被重复更新");
    }

    @Test
    void unknownStageAndInvalidPayloadAreSkipped() {
        // 未知阶段：新事件但无法识别 → 不落漏斗
        service.handle(event("e2", AnalyticsEventType.SESSION_LIFECYCLE, "{\"stage\":\"unknown\"}"));
        // 损坏的事件体：解析失败 → 降级跳过，不抛错
        service.handle(event("e3", AnalyticsEventType.SESSION_LIFECYCLE, "{not json"));
        // 缺 stage：跳过
        service.handle(event("e4", AnalyticsEventType.SESSION_LIFECYCLE, "{}"));

        assertTrue(reportRepository.stages.isEmpty(), "非法规避不了漏斗写入");
        assertEquals(3, eventRepository.eventIds.size(), "明细仍应留档（便于排查）");
    }

    @Test
    void usageEventAccumulatesTokensWithShanghaiStatDate() {
        service.handle(event("e5", AnalyticsEventType.LLM_USAGE,
                "{\"provider\":\"deepseek\",\"model\":\"deepseek-chat\",\"prompt_tokens\":120,"
                        + "\"completion_tokens\":45,\"estimated\":false,\"first_token_ms\":640}"));

        assertTrue(reportRepository.tokenAccumulated);
        assertEquals("deepseek", reportRepository.lastProvider);
        assertEquals(120L, reportRepository.lastPromptTokens);
        assertEquals(45L, reportRepository.lastCompletionTokens);
        assertFalse(reportRepository.lastEstimated);
        assertEquals(640L, reportRepository.lastFirstTokenMs);
        assertEquals(Instant.ofEpochMilli(OCCURRED_AT).atZone(ZONE).toLocalDate().toString(),
                reportRepository.lastStatDate, "统计日按 Asia/Shanghai 归集");
    }

    @Test
    void usageEventMarksEstimatedWhenProviderHasNoUsage() {
        service.handle(event("e6", AnalyticsEventType.LLM_USAGE,
                "{\"provider\":\"kimi\",\"model\":\"moonshot\",\"prompt_tokens\":7,"
                        + "\"completion_tokens\":3,\"estimated\":true}"));

        assertTrue(reportRepository.lastEstimated, "估算条数须标记，报表据此提示");
        assertEquals(0L, reportRepository.lastFirstTokenMs, "无样本时首字延迟记 0");
    }

    @Test
    void feedbackEventIsSavedAndInvalidRatingSkipped() {
        service.handle(event("e7", AnalyticsEventType.SESSION_FEEDBACK,
                "{\"rating\":5,\"comment\":\"很满意\",\"user_id\":\"u1\",\"tags\":\"退款\"}"));

        assertEquals(1, reportRepository.feedbacks.size());
        assertEquals(5, reportRepository.feedbacks.get(0).rating());
        assertEquals("tenant-1", reportRepository.feedbacks.get(0).tenantId(), "租户来自事件信封");
        assertEquals("u1", reportRepository.feedbacks.get(0).userId());

        service.handle(event("e8", AnalyticsEventType.SESSION_FEEDBACK, "{\"rating\":9}"));
        assertEquals(1, reportRepository.feedbacks.size(), "非法评分不得落库");
    }

    // ---------- 内存替身 ----------

    /** 幂等明细仓储替身：以 event_id 集合模拟主键去重。 */
    private static final class InMemoryEventRepository implements AnalyticsEventRepository {

        private final Set<String> eventIds = new HashSet<>();

        @Override
        public boolean insertIgnore(AnalyticsEvent event) {
            return eventIds.add(event.eventId());
        }
    }

    /** 聚合仓储替身：记录最近一次写入，供断言。 */
    private static final class InMemoryReportRepository implements ReportRepository {

        private final List<FunnelStage> stages = new ArrayList<>();
        private final List<FeedbackRecord> feedbacks = new ArrayList<>();
        private String lastChannel = "";
        private boolean tokenAccumulated;
        private String lastStatDate = "";
        private String lastProvider = "";
        private long lastPromptTokens;
        private long lastCompletionTokens;
        private boolean lastEstimated;
        private long lastFirstTokenMs;

        @Override
        public void recordFunnelStage(String sessionId, String tenantId, String channel,
                                      FunnelStage stage, long occurredAtMs) {
            stages.add(stage);
            lastChannel = channel;
        }

        @Override
        public void accumulateTokenUsage(String statDate, String tenantId, String provider,
                                         String model, long promptTokens, long completionTokens,
                                         boolean estimated, long firstTokenMs) {
            tokenAccumulated = true;
            lastStatDate = statDate;
            lastProvider = provider;
            lastPromptTokens = promptTokens;
            lastCompletionTokens = completionTokens;
            lastEstimated = estimated;
            lastFirstTokenMs = firstTokenMs;
        }

        @Override
        public void saveFeedback(String feedbackId, String sessionId, String tenantId, String userId,
                                 int rating, String comment, String tags, long createdAtMs) {
            feedbacks.add(new FeedbackRecord(sessionId, tenantId, userId, rating, comment));
        }

        @Override
        public java.util.Map<FunnelStage, Integer> countFunnelStages(String tenantId, long fromMs,
                                                                    long toMs) {
            return java.util.Map.of();
        }

        @Override
        public List<com.lingxi.report.domain.TokenUsageRow> queryTokenUsage(String tenantId,
                                                                           String fromDate,
                                                                           String toDate) {
            return List.of();
        }

        @Override
        public List<com.lingxi.report.domain.FeedbackItem> queryRecentFeedback(String tenantId,
                                                                             long fromMs, long toMs,
                                                                             int limit) {
            return List.of();
        }

        @Override
        public java.util.Map<Integer, Integer> queryRatingDistribution(String tenantId, long fromMs,
                                                                     long toMs) {
            return java.util.Map.of();
        }

        private record FeedbackRecord(String sessionId, String tenantId, String userId, int rating,
                                      String comment) {
        }
    }
}
