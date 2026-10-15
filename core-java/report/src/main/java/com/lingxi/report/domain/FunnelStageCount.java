package com.lingxi.report.domain;

/**
 * 漏斗阶段统计（报表出参）。
 *
 * @param stage     阶段
 * @param sessions  到达该阶段的会话数
 * @param stepRate  相对上一阶段的转化率（首个阶段固定 1.0；上一阶段为 0 时为 0.0）
 * @param totalRate 相对「建会话」的累计转化率
 */
public record FunnelStageCount(FunnelStage stage, int sessions, double stepRate, double totalRate) {

    /** 展示名（报表用）。 */
    public String label() {
        return stage.label();
    }

    /** 线上标识。 */
    public String stageWire() {
        return stage.wire();
    }
}
