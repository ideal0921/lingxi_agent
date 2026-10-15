package com.lingxi.session.ai;

import java.util.List;

/**
 * 回复建议结果（M4-4.6）。
 *
 * <p>同步端口与协调器共用：既承载「成功候选」，也承载「降级 + 是否已转入异步队列」，
 * 供 gRPC 层如实填充 {@code ok/degraded/degrade_reason/queued}，避免对前端谎报。</p>
 *
 * @param ok            是否成功产出候选（降级时为 false）
 * @param suggestions   候选回复草稿（1~3 条；降级时为空）
 * @param degraded      是否降级（BUSY / TIMEOUT / UNAVAILABLE）
 * @param degradeReason 降级原因
 * @param elapsedMs     本次同步耗时（毫秒）
 * @param queued        是否已转入异步队列（true 时结果稍后经坐席端推送）
 * @param historyCount  实际注入的上下文消息条数（观测用）
 */
public record SuggestResult(
        boolean ok,
        List<String> suggestions,
        boolean degraded,
        String degradeReason,
        long elapsedMs,
        boolean queued,
        int historyCount) {

    /** 成功产出候选（队列标记与上下文条数由协调器补齐）。 */
    public static SuggestResult ok(List<String> suggestions, long elapsedMs) {
        return new SuggestResult(true, List.copyOf(suggestions), false, "", elapsedMs, false, 0);
    }

    /** 降级结果；{@code reason} 为空时按 {@code UNAVAILABLE} 处理。 */
    public static SuggestResult degraded(String reason, long elapsedMs) {
        return new SuggestResult(false, List.of(), true,
                reason == null || reason.isBlank() ? "UNAVAILABLE" : reason, elapsedMs, false, 0);
    }

    /** 派生副本：仅替换 {@code queued}（record 不可变，便于协调器按入队结果改写）。 */
    public SuggestResult withQueued(boolean queuedValue) {
        return new SuggestResult(ok, suggestions, degraded, degradeReason, elapsedMs, queuedValue, historyCount);
    }

    /** 派生副本：仅替换 {@code historyCount}（上下文条数由协调器组装时得知）。 */
    public SuggestResult withHistoryCount(int count) {
        return new SuggestResult(ok, suggestions, degraded, degradeReason, elapsedMs, queued, count);
    }
}
