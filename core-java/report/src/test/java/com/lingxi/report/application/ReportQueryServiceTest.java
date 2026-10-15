package com.lingxi.report.application;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.report.domain.CsatSnapshot;
import com.lingxi.report.domain.FeedbackItem;
import com.lingxi.report.domain.FunnelStage;
import com.lingxi.report.domain.FunnelStageCount;
import com.lingxi.report.domain.TokenReport;
import com.lingxi.report.domain.TokenUsageRow;
import com.lingxi.report.domain.repo.ReportRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表查询编排测试（M5-5.1，内存替身，不连 MySQL）。
 *
 * <p>覆盖：漏斗转化率计算（相邻 / 累计 / 零基分母）、Token 合计、CSAT 计算与评分分布补齐、
 * 区间兜底（缺省最近 7 天）与租户过滤透传。</p>
 */
class ReportQueryServiceTest {

    private InMemoryQueryRepository repository;
    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryQueryRepository();
        service = new ReportQueryService(repository);
    }

    @Test
    void funnelComputesStepAndTotalRates() {
        repository.funnelCounts.put(FunnelStage.CREATED, 10);
        repository.funnelCounts.put(FunnelStage.USER_MSG, 8);
        repository.funnelCounts.put(FunnelStage.AI_REPLIED, 6);
        repository.funnelCounts.put(FunnelStage.HANDOFF, 2);
        repository.funnelCounts.put(FunnelStage.CLOSED, 1);

        List<FunnelStageCount> stages = service.funnel("tenant-1", 1000L, 2000L);

        assertEquals(5, stages.size(), "五个阶段全覆盖");
        assertEquals(FunnelStage.CREATED, stages.get(0).stage());
        assertEquals(1.0, stages.get(0).stepRate(), "首个阶段转化率固定 1.0");
        assertEquals(1.0, stages.get(0).totalRate());
        assertEquals(0.8, stages.get(1).stepRate(), "8/10");
        assertEquals(0.75, stages.get(2).stepRate(), "6/8");
        assertEquals(0.1, stages.get(4).totalRate(), "1/10");
        assertEquals("建会话", stages.get(0).label(), "展示名随阶段");
        assertEquals("tenant-1", repository.lastTenantId, "租户必须透传给仓储过滤");
    }

    @Test
    void funnelHandlesZeroBaseWithoutDivisionByZero() {
        List<FunnelStageCount> stages = service.funnel("tenant-1", 1000L, 2000L);

        assertEquals(0, stages.get(0).sessions());
        assertEquals(0.0, stages.get(0).stepRate(), "无建会话时转化率为 0（不抛错）");
        assertTrue(stages.stream().allMatch(stage -> stage.totalRate() == 0.0));
    }

    @Test
    void tokenReportAggregatesTotals() {
        repository.tokenRows = List.of(
                new TokenUsageRow("2026-10-15", "deepseek", "deepseek-chat", 3, 300, 120, 1, 2, 1200),
                new TokenUsageRow("2026-10-15", "kimi", "moonshot", 2, 80, 40, 2, 0, 0));

        TokenReport report = service.tokens("tenant-1", 1000L, 2000L);

        assertEquals(5, report.totalRequests());
        assertEquals(380L, report.totalPromptTokens());
        assertEquals(160L, report.totalCompletionTokens());
        assertEquals(3, report.totalEstimatedRequests(), "估算条数合计供报表提示");
        assertEquals(600L, report.rows().get(0).firstTokenMsAvg(), "1200/2");
        assertEquals(0L, report.rows().get(1).firstTokenMsAvg(), "无样本时为 0");
        assertTrue(report.rows().get(1).allEstimated(), "全部估算时应标记");
    }

    @Test
    void csatComputesAverageAndDistributionWithAllRatings() {
        repository.ratingDistribution.put(5, 3);
        repository.ratingDistribution.put(4, 1);
        repository.ratingDistribution.put(3, 1);
        repository.recentFeedback = List.of(new FeedbackItem("s-1", 5, "很好", 900L));

        CsatSnapshot snapshot = service.csat("tenant-1", 1000L, 2000L);

        assertEquals(5, snapshot.feedbackCount());
        assertEquals(4.4, snapshot.averageRating(), "(15+4+3)/5");
        assertEquals(0.8, snapshot.csat(), "评分 ≥4 的 4 条 / 5 条");
        assertEquals(5, snapshot.distribution().size(), "1~5 星全覆盖（缺失补 0）");
        assertEquals(List.of(5, 4, 3, 2, 1), new ArrayList<>(snapshot.distribution().keySet()),
                "按 5→1 星降序展示");
        assertEquals(0, snapshot.distribution().get(2));
        assertEquals(1, snapshot.recent().size());
    }

    @Test
    void csatWithoutFeedbackReturnsZeros() {
        CsatSnapshot snapshot = service.csat("tenant-1", 1000L, 2000L);

        assertEquals(0, snapshot.feedbackCount());
        assertEquals(0.0, snapshot.averageRating(), "无评价时不得除零");
        assertEquals(0.0, snapshot.csat());
    }

    @Test
    void missingRangeFallsBackToRecentWindow() {
        long now = System.currentTimeMillis();
        service.funnel("tenant-1", 0L, 0L);

        assertTrue(repository.lastToMs > now - 5000 && repository.lastToMs <= now + 5000,
                "结束时间缺省为当前时间");
        assertEquals(7L * 24 * 60 * 60 * 1000, repository.lastToMs - repository.lastFromMs,
                "起始时间缺省为最近 7 天");
    }

    // ---------- 内存替身 ----------

    /** 查询仓储替身：只回放预设数据并记录调用参数。 */
    private static final class InMemoryQueryRepository implements ReportRepository {

        private final Map<FunnelStage, Integer> funnelCounts = new EnumMap<>(FunnelStage.class);
        private List<TokenUsageRow> tokenRows = List.of();
        private Map<Integer, Integer> ratingDistribution = new LinkedHashMap<>();
        private List<FeedbackItem> recentFeedback = List.of();
        private String lastTenantId = "";
        private long lastFromMs;
        private long lastToMs;

        @Override
        public void recordFunnelStage(String sessionId, String tenantId, String channel,
                                      FunnelStage stage, long occurredAtMs) {
            throw new UnsupportedOperationException("查询服务不应调用写路径");
        }

        @Override
        public void accumulateTokenUsage(String statDate, String tenantId, String provider,
                                         String model, long promptTokens, long completionTokens,
                                         boolean estimated, long firstTokenMs) {
            throw new UnsupportedOperationException("查询服务不应调用写路径");
        }

        @Override
        public void saveFeedback(String feedbackId, String sessionId, String tenantId, String userId,
                                 int rating, String comment, String tags, long createdAtMs) {
            throw new UnsupportedOperationException("查询服务不应调用写路径");
        }

        @Override
        public Map<FunnelStage, Integer> countFunnelStages(String tenantId, long fromMs, long toMs) {
            lastTenantId = tenantId;
            lastFromMs = fromMs;
            lastToMs = toMs;
            return funnelCounts;
        }

        @Override
        public List<TokenUsageRow> queryTokenUsage(String tenantId, String fromDate, String toDate) {
            lastTenantId = tenantId;
            return tokenRows;
        }

        @Override
        public List<FeedbackItem> queryRecentFeedback(String tenantId, long fromMs, long toMs, int limit) {
            lastTenantId = tenantId;
            return recentFeedback;
        }

        @Override
        public Map<Integer, Integer> queryRatingDistribution(String tenantId, long fromMs, long toMs) {
            lastTenantId = tenantId;
            return ratingDistribution;
        }
    }
}
