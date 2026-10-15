package com.lingxi.report.domain;

/**
 * 分析事件类型（M5-5.1）：与 {@code lingxi.analytics.event} 事件体的 {@code type} 字段一一对应。
 */
public enum AnalyticsEventType {

    /** 会话生命周期（建会话/用户消息/AI 应答/转人工/关闭）。 */
    SESSION_LIFECYCLE("session_lifecycle"),
    /** LLM Token 用量与延迟。 */
    LLM_USAGE("llm_usage"),
    /** 会话满意度评价。 */
    SESSION_FEEDBACK("session_feedback");

    private final String wire;

    AnalyticsEventType(String wire) {
        this.wire = wire;
    }

    /** 线上标识（事件体 type 取值）。 */
    public String wire() {
        return wire;
    }

    /**
     * 解析线上标识。
     *
     * @param wire 事件体 type 取值
     * @return 匹配的类型；无法识别时返回 {@code null}（调用方忽略该事件）
     */
    public static AnalyticsEventType fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return null;
        }
        for (AnalyticsEventType type : values()) {
            if (type.wire.equalsIgnoreCase(wire.trim())) {
                return type;
            }
        }
        return null;
    }
}
