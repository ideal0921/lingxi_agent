package com.lingxi.workflow.domain;

/**
 * 编排节点类型：与前端画布节点面板、图定义 JSON Schema 一一对应。
 *
 * <p>{@code wire} 为跨语言线上标识（JSON 中 node.type 小写取值），
 * 与 M4-4.8 运行时加载图时使用的取值保持一致。</p>
 */
public enum NodeType {

    /** 大模型生成节点。 */
    LLM("llm"),
    /** 知识检索节点。 */
    RETRIEVE("retrieve"),
    /** 工具调用节点。 */
    TOOL("tool"),
    /** 条件分支节点。 */
    BRANCH("branch");

    private final String wire;

    NodeType(String wire) {
        this.wire = wire;
    }

    /** 线上标识（JSON 取值）。 */
    public String wire() {
        return wire;
    }

    /**
     * 解析线上小写标识。
     *
     * @param wire JSON 中的 type 取值
     * @return 匹配的枚举；无法识别或入参为空时返回 {@code null}，由调用方转业务异常
     */
    public static NodeType fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return null;
        }
        for (NodeType type : values()) {
            if (type.wire.equalsIgnoreCase(wire.trim())) {
                return type;
            }
        }
        return null;
    }
}
