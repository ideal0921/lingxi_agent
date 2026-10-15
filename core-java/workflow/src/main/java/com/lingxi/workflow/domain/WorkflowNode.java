package com.lingxi.workflow.domain;

import java.util.Map;

/**
 * 编排节点（图定义解析产物）。
 *
 * @param id     节点标识（画布内唯一）
 * @param type   节点类型
 * @param name   展示名（画布标签）
 * @param config 节点配置（开放键值，各类型必填项由 {@link WorkflowGraphValidator} 校验）
 */
public record WorkflowNode(String id, NodeType type, String name, Map<String, Object> config) {

    /** 读取配置项并转成非空字符串；缺失或非字符串时返回 {@code null}。 */
    public String configString(String key) {
        Object value = config == null ? null : config.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
