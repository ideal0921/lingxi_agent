package com.lingxi.workflow.domain;

/**
 * 边的条件标记：分支节点的两条出边分别取 {@link #TRUE} / {@link #FALSE}，
 * 其余节点的出边一律为 {@link #ALWAYS}。
 *
 * <p>本期不引入表达式求值引擎：{@code branch} 节点仅存储条件字符串，
 * 由 M4-4.8 运行时以白名单方式评估，避免用户自定义表达式带来的注入风险。</p>
 */
public enum EdgeCondition {

    /** 无条件流转（非分支节点的唯一合法取值）。 */
    ALWAYS("always"),
    /** 分支条件为真。 */
    TRUE("true"),
    /** 分支条件为假。 */
    FALSE("false");

    private final String wire;

    EdgeCondition(String wire) {
        this.wire = wire;
    }

    /** 线上标识（JSON 取值）。 */
    public String wire() {
        return wire;
    }

    /**
     * 解析线上小写标识；缺失时默认 {@link #ALWAYS}（前端未显式标注条件的普通边）。
     *
     * @param wire JSON 中的 condition 取值
     * @return 匹配的枚举；显式给出但无法识别时返回 {@code null}
     */
    public static EdgeCondition fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return ALWAYS;
        }
        for (EdgeCondition condition : values()) {
            if (condition.wire.equalsIgnoreCase(wire.trim())) {
                return condition;
            }
        }
        return null;
    }
}
