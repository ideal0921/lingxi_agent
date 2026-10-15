package com.lingxi.report.domain;

import java.util.List;
import java.util.Map;

/**
 * 满意度（CSAT）报表（出参）。
 *
 * @param feedbackCount  评价总数
 * @param averageRating  平均分（无评价时为 0）
 * @param csat           CSAT = 评分 ≥4 的评价数 / 评价总数（无评价时为 0）
 * @param distribution   评分分布：评分 → 条数（1~5 全覆盖，缺失记 0）
 * @param recent         最近评价明细
 */
public record CsatSnapshot(int feedbackCount, double averageRating, double csat,
                           Map<Integer, Integer> distribution, List<FeedbackItem> recent) {
}
