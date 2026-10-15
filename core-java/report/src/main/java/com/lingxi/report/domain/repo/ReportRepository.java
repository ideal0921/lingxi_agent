package com.lingxi.report.domain.repo;

import java.util.List;
import java.util.Map;

import com.lingxi.report.domain.FeedbackItem;
import com.lingxi.report.domain.FunnelStage;
import com.lingxi.report.domain.TokenUsageRow;

/**
 * 报表聚合与查询仓储端口（M5-5.1）：依赖倒置，本期由 MySQL 实现，
 * 将来切 ClickHouse 只需替换实现，domain/application 零改动。
 *
 * <p><b>租户隔离</b>：所有查询方法均强制携带 {@code tenantId}，跨租户不合算。</p>
 */
public interface ReportRepository {

    /**
     * 记录漏斗阶段时间戳：同一会话同阶段取**最早**（{@code LEAST(旧值, 新值)}）。
     *
     * @param channel 渠道（仅建会话时已知，其余阶段可传空串；已存在时以先写入者为准）
     */
    void recordFunnelStage(String sessionId, String tenantId, String channel, FunnelStage stage,
                           long occurredAtMs);

    /**
     * 累加 Token 用量（日 × 租户 × 提供方 × 模型）。
     *
     * @param firstTokenMs 首字延迟；{@code <=0} 表示无样本，不计入平均值的分母
     * @param estimated    是否为估算值（无法取得真实 usage 时）
     */
    void accumulateTokenUsage(String statDate, String tenantId, String provider, String model,
                              long promptTokens, long completionTokens, boolean estimated,
                              long firstTokenMs);

    /** 保存满意度评价（会话维度唯一，重复提交覆盖）。 */
    void saveFeedback(String feedbackId, String sessionId, String tenantId, String userId,
                      int rating, String comment, String tags, long createdAtMs);

    /** 按阶段统计到达会话数（区间内建会话的会话，按 created 时间过滤）。 */
    Map<FunnelStage, Integer> countFunnelStages(String tenantId, long fromMs, long toMs);

    /** 查询日聚合明细（按日与提供方升序）。 */
    List<TokenUsageRow> queryTokenUsage(String tenantId, String fromDate, String toDate);

    /** 查询最近评价明细（按时间倒序）。 */
    List<FeedbackItem> queryRecentFeedback(String tenantId, long fromMs, long toMs, int limit);

    /** 查询评分分布（评分 → 条数）。 */
    Map<Integer, Integer> queryRatingDistribution(String tenantId, long fromMs, long toMs);
}
