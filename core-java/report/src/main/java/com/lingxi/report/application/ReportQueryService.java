package com.lingxi.report.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.lingxi.report.domain.CsatSnapshot;
import com.lingxi.report.domain.FeedbackItem;
import com.lingxi.report.domain.FunnelStage;
import com.lingxi.report.domain.FunnelStageCount;
import com.lingxi.report.domain.TokenReport;
import com.lingxi.report.domain.TokenUsageRow;
import com.lingxi.report.domain.repo.ReportRepository;

/**
 * 报表查询编排（M5-5.1）：会话漏斗 / Token 消耗 / 满意度（CSAT）。
 *
 * <p>口径统一在应用层计算（仓储只做取数），便于离线单测；所有查询强制携带租户，
 * 区间缺省为「最近 7 天」。</p>
 */
@Service
public class ReportQueryService {

    /** 统计时区（与统计日落库口径一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 缺省查询窗口（天）。 */
    private static final long DEFAULT_WINDOW_DAYS = 7L;
    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    /** 最近评价明细条数上限。 */
    private static final int RECENT_FEEDBACK_LIMIT = 50;

    private final ReportRepository reportRepository;

    public ReportQueryService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /**
     * 会话漏斗：各阶段到达会话数 + 相邻转化率 + 相对建会话的累计转化率。
     *
     * @param fromMs 起始时间（{@code <=0} 取默认窗口）
     * @param toMs   结束时间（{@code <=0} 取当前时间）
     */
    public List<FunnelStageCount> funnel(String tenantId, long fromMs, long toMs) {
        long[] range = normalizeRange(fromMs, toMs);
        Map<FunnelStage, Integer> counts = reportRepository.countFunnelStages(tenantId, range[0], range[1]);
        FunnelStage[] stages = FunnelStage.values();
        int base = counts.getOrDefault(FunnelStage.CREATED, 0);
        List<FunnelStageCount> result = new ArrayList<>(stages.length);
        for (int index = 0; index < stages.length; index++) {
            int sessions = counts.getOrDefault(stages[index], 0);
            double stepRate;
            if (index == 0) {
                stepRate = base > 0 ? 1.0 : 0.0;
            } else {
                int previous = counts.getOrDefault(stages[index - 1], 0);
                stepRate = previous > 0 ? (double) sessions / previous : 0.0;
            }
            double totalRate = base > 0 ? (double) sessions / base : 0.0;
            result.add(new FunnelStageCount(stages[index], sessions, stepRate, totalRate));
        }
        return result;
    }

    /** Token 消耗：日 × 提供方 × 模型 明细 + 合计。 */
    public TokenReport tokens(String tenantId, long fromMs, long toMs) {
        long[] range = normalizeRange(fromMs, toMs);
        List<TokenUsageRow> rows = reportRepository.queryTokenUsage(
                tenantId, statDate(range[0]), statDate(range[1]));
        int totalRequests = 0;
        int totalEstimated = 0;
        long totalPrompt = 0;
        long totalCompletion = 0;
        for (TokenUsageRow row : rows) {
            totalRequests += row.requests();
            totalEstimated += row.estimatedRequests();
            totalPrompt += row.promptTokens();
            totalCompletion += row.completionTokens();
        }
        return new TokenReport(totalRequests, totalPrompt, totalCompletion, totalEstimated, rows);
    }

    /** 满意度：评价数 / 平均分 / CSAT / 评分分布 / 最近评价。 */
    public CsatSnapshot csat(String tenantId, long fromMs, long toMs) {
        long[] range = normalizeRange(fromMs, toMs);
        Map<Integer, Integer> raw = reportRepository.queryRatingDistribution(tenantId, range[0], range[1]);
        List<FeedbackItem> recent = reportRepository.queryRecentFeedback(tenantId, range[0], range[1],
                RECENT_FEEDBACK_LIMIT);

        int count = 0;
        int positive = 0;
        long ratingSum = 0;
        // 报表按 5→1 星降序展示（更符合阅读习惯），缺失星级补 0
        Map<Integer, Integer> distribution = new LinkedHashMap<>();
        for (int rating = 5; rating >= 1; rating--) {
            int bucket = raw.getOrDefault(rating, 0);
            distribution.put(rating, bucket);
            count += bucket;
            ratingSum += (long) rating * bucket;
            if (rating >= 4) {
                positive += bucket;
            }
        }
        double average = count > 0 ? (double) ratingSum / count : 0.0;
        double csat = count > 0 ? (double) positive / count : 0.0;
        return new CsatSnapshot(count, average, csat, distribution, recent);
    }

    /** 时间区间兜底：缺省为最近 7 天；区间反置时纠正。 */
    private static long[] normalizeRange(long fromMs, long toMs) {
        long end = toMs > 0 ? toMs : System.currentTimeMillis();
        long start = fromMs > 0 ? fromMs : end - DEFAULT_WINDOW_DAYS * MILLIS_PER_DAY;
        return start <= end ? new long[] {start, end} : new long[] {end, start};
    }

    /** epoch ms → 统计日（Asia/Shanghai）。 */
    private static String statDate(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate().toString();
    }
}
