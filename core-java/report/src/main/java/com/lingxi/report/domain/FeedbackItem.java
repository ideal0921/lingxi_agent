package com.lingxi.report.domain;

/**
 * 会话评价明细（报表出参）。
 *
 * @param sessionId   会话 ID
 * @param rating      评分 1~5
 * @param comment     评价内容
 * @param createdAtMs 评价时间（epoch ms）
 */
public record FeedbackItem(String sessionId, int rating, String comment, long createdAtMs) {
}
