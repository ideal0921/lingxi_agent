package com.lingxi.report.domain;

import java.util.List;

/**
 * Token 报表（出参）：合计 + 明细行。
 *
 * @param totalRequests         调用次数合计
 * @param totalPromptTokens     输入 token 合计
 * @param totalCompletionTokens 输出 token 合计
 * @param totalEstimatedRequests 估算条数合计（占比提示用）
 * @param rows                  明细行（日 × 提供方 × 模型）
 */
public record TokenReport(int totalRequests, long totalPromptTokens, long totalCompletionTokens,
                          int totalEstimatedRequests, List<TokenUsageRow> rows) {
}
