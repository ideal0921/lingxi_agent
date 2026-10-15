package com.lingxi.report.infrastructure.repo;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.lingxi.report.domain.FeedbackItem;
import com.lingxi.report.domain.FunnelStage;
import com.lingxi.report.domain.TokenUsageRow;
import com.lingxi.report.domain.repo.ReportRepository;

/**
 * MySQL 报表聚合与查询实现（M5-5.1）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>幂等</b>：漏斗阶段用 {@code INSERT ... ON DUPLICATE KEY UPDATE} + {@code LEAST} 取最早，
 *       故「每条用户消息都发埋点」不会写错首条时间；Token 用 {@code +=} 累加，
 *       但仅在明细表插入成功（新事件）时才调用，重投不会重复累加；</li>
 *   <li><b>依赖倒置</b>：本类只实现 {@link ReportRepository} 端口，将来切 ClickHouse
 *       只需新增实现类，domain/application 零改动；</li>
 *   <li><b>租户隔离</b>：所有查询强制 {@code tenant_id} 条件。</li>
 * </ul>
 */
@Repository
public class MySqlReportRepository implements ReportRepository {

    private static final String FUNNEL_TABLE = "lingxi_report_session_funnel";
    private static final String TOKEN_TABLE = "lingxi_report_token_daily";
    private static final String FEEDBACK_TABLE = "lingxi_report_feedback";

    private final JdbcTemplate jdbcTemplate;

    public MySqlReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void recordFunnelStage(String sessionId, String tenantId, String channel, FunnelStage stage,
                                  long occurredAtMs) {
        String column = columnOf(stage);
        // 首次写入建行（含渠道），重复写入只把该阶段时间戳取更早者（LEAST）
        jdbcTemplate.update("""
                INSERT INTO %s (session_id, tenant_id, channel, %s, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    %s = LEAST(COALESCE(%s, VALUES(%s)), VALUES(%s)),
                    updated_at = VALUES(updated_at)
                """.formatted(FUNNEL_TABLE, column, column, column, column, column),
                sessionId, tenantId, channel == null ? "" : channel, occurredAtMs,
                System.currentTimeMillis());
    }

    @Override
    public void accumulateTokenUsage(String statDate, String tenantId, String provider, String model,
                                     long promptTokens, long completionTokens, boolean estimated,
                                     long firstTokenMs) {
        boolean hasFirstTokenSample = firstTokenMs > 0;
        jdbcTemplate.update("""
                INSERT INTO %s (stat_date, tenant_id, provider, model, requests, prompt_tokens,
                                completion_tokens, estimated_requests, first_token_samples,
                                first_token_ms_sum, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    requests = requests + 1,
                    prompt_tokens = prompt_tokens + VALUES(prompt_tokens),
                    completion_tokens = completion_tokens + VALUES(completion_tokens),
                    estimated_requests = estimated_requests + VALUES(estimated_requests),
                    first_token_samples = first_token_samples + VALUES(first_token_samples),
                    first_token_ms_sum = first_token_ms_sum + VALUES(first_token_ms_sum),
                    updated_at = VALUES(updated_at)
                """.formatted(TOKEN_TABLE),
                statDate, tenantId, provider == null ? "" : provider, model == null ? "" : model,
                promptTokens, completionTokens, estimated ? 1 : 0,
                hasFirstTokenSample ? 1 : 0, Math.max(0L, firstTokenMs),
                System.currentTimeMillis());
    }

    @Override
    public void saveFeedback(String feedbackId, String sessionId, String tenantId, String userId,
                             int rating, String comment, String tags, long createdAtMs) {
        // 会话维度唯一（uk_session）：后评覆盖前评，不新增记录
        jdbcTemplate.update("""
                INSERT INTO %s (feedback_id, session_id, tenant_id, user_id, rating, comment, tags, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    rating = VALUES(rating), comment = VALUES(comment),
                    tags = VALUES(tags), created_at = VALUES(created_at)
                """.formatted(FEEDBACK_TABLE),
                feedbackId, sessionId, tenantId, userId == null ? "" : userId, rating,
                comment == null ? "" : comment, tags == null ? "" : tags, createdAtMs);
    }

    @Override
    public Map<FunnelStage, Integer> countFunnelStages(String tenantId, long fromMs, long toMs) {
        String sql = """
                SELECT
                    SUM(created_ms IS NOT NULL)        AS created_cnt,
                    SUM(first_user_msg_ms IS NOT NULL) AS user_msg_cnt,
                    SUM(first_ai_reply_ms IS NOT NULL) AS ai_replied_cnt,
                    SUM(handoff_ms IS NOT NULL)        AS handoff_cnt,
                    SUM(closed_ms IS NOT NULL)         AS closed_cnt
                FROM %s
                WHERE tenant_id = ? AND created_ms >= ? AND created_ms < ?
                """.formatted(FUNNEL_TABLE);
        Map<FunnelStage, Integer> counts = new EnumMap<>(FunnelStage.class);
        jdbcTemplate.query(sql, rs -> {
            counts.put(FunnelStage.CREATED, rs.getInt("created_cnt"));
            counts.put(FunnelStage.USER_MSG, rs.getInt("user_msg_cnt"));
            counts.put(FunnelStage.AI_REPLIED, rs.getInt("ai_replied_cnt"));
            counts.put(FunnelStage.HANDOFF, rs.getInt("handoff_cnt"));
            counts.put(FunnelStage.CLOSED, rs.getInt("closed_cnt"));
        }, tenantId, fromMs, toMs);
        return counts;
    }

    @Override
    public List<TokenUsageRow> queryTokenUsage(String tenantId, String fromDate, String toDate) {
        return jdbcTemplate.query("""
                SELECT stat_date, provider, model, requests, prompt_tokens, completion_tokens,
                       estimated_requests, first_token_samples, first_token_ms_sum
                FROM %s
                WHERE tenant_id = ? AND stat_date BETWEEN ? AND ?
                ORDER BY stat_date ASC, requests DESC
                """.formatted(TOKEN_TABLE),
                (rs, rowNum) -> new TokenUsageRow(
                        rs.getString("stat_date"), rs.getString("provider"), rs.getString("model"),
                        rs.getInt("requests"), rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                        rs.getInt("estimated_requests"), rs.getInt("first_token_samples"),
                        rs.getLong("first_token_ms_sum")),
                tenantId, fromDate, toDate);
    }

    @Override
    public List<FeedbackItem> queryRecentFeedback(String tenantId, long fromMs, long toMs, int limit) {
        return jdbcTemplate.query("""
                SELECT session_id, rating, comment, created_at
                FROM %s
                WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                ORDER BY created_at DESC
                LIMIT ?
                """.formatted(FEEDBACK_TABLE),
                (rs, rowNum) -> new FeedbackItem(rs.getString("session_id"), rs.getInt("rating"),
                        rs.getString("comment"), rs.getLong("created_at")),
                tenantId, fromMs, toMs, Math.max(1, limit));
    }

    @Override
    public Map<Integer, Integer> queryRatingDistribution(String tenantId, long fromMs, long toMs) {
        Map<Integer, Integer> distribution = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT rating, COUNT(*) AS cnt
                FROM %s
                WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                GROUP BY rating
                """.formatted(FEEDBACK_TABLE),
                rs -> {
                    distribution.put(rs.getInt("rating"), rs.getInt("cnt"));
                },
                tenantId, fromMs, toMs);
        return distribution;
    }

    /** 漏斗阶段 → 列名（枚举取值，非用户输入，无注入风险）。 */
    private static String columnOf(FunnelStage stage) {
        return switch (stage) {
            case CREATED -> "created_ms";
            case USER_MSG -> "first_user_msg_ms";
            case AI_REPLIED -> "first_ai_reply_ms";
            case HANDOFF -> "handoff_ms";
            case CLOSED -> "closed_ms";
        };
    }
}
