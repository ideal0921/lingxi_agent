package com.lingxi.report.domain;

/**
 * 会话漏斗阶段（M5-5.1）：与埋点事件体的 {@code stage} 字段一一对应。
 *
 * <p>各阶段在漏斗表中存「**最早**时间戳」（消费侧用 {@code LEAST} 归并），因此每条用户消息
 * 都发 {@code USER_MSG} 也不会把「首条用户消息」写错。</p>
 */
public enum FunnelStage {

    /** 建会话（漏斗分母）。 */
    CREATED("created", "建会话"),
    /** 用户首条消息。 */
    USER_MSG("user_msg", "首条消息"),
    /** 首次 AI 应答。 */
    AI_REPLIED("ai_replied", "AI 应答"),
    /** 首次转人工 / 坐席接管。 */
    HANDOFF("handoff", "转人工"),
    /** 会话关闭。 */
    CLOSED("closed", "关闭");

    private final String wire;
    private final String label;

    FunnelStage(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    /** 线上标识（事件体 stage 取值）。 */
    public String wire() {
        return wire;
    }

    /** 报表展示名。 */
    public String label() {
        return label;
    }

    /**
     * 解析线上标识。
     *
     * @param wire 事件体 stage 取值
     * @return 匹配的阶段；无法识别时返回 {@code null}（调用方忽略该事件）
     */
    public static FunnelStage fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return null;
        }
        for (FunnelStage stage : values()) {
            if (stage.wire.equalsIgnoreCase(wire.trim())) {
                return stage;
            }
        }
        return null;
    }
}
