package com.lingxi.report.domain;

/**
 * Token 消耗聚合行（M5-5.1）：日 × 租户 × 提供方 × 模型。
 *
 * @param statDate            统计日（yyyy-MM-dd，Asia/Shanghai）
 * @param provider            模型提供方
 * @param model               模型名
 * @param requests            LLM 调用次数（工具多轮各计一次）
 * @param promptTokens        输入 token 合计
 * @param completionTokens    输出 token 合计
 * @param estimatedRequests   其中无法取得真实 usage、按字符数估算的条数
 * @param firstTokenSamples   有首字延迟样本的条数（用作平均值的分母）
 * @param firstTokenMsSum     首字延迟合计（ms）
 */
public record TokenUsageRow(String statDate, String provider, String model, int requests,
                            long promptTokens, long completionTokens, int estimatedRequests,
                            int firstTokenSamples, long firstTokenMsSum) {

    /** 平均首字延迟（ms）；无样本时为 0。 */
    public long firstTokenMsAvg() {
        return firstTokenSamples <= 0 ? 0L : firstTokenMsSum / firstTokenSamples;
    }

    /** 是否全部为估算值（报表提示用）。 */
    public boolean allEstimated() {
        return requests > 0 && estimatedRequests >= requests;
    }
}
