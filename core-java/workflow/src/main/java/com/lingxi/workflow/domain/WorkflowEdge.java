package com.lingxi.workflow.domain;

/**
 * 编排连线（图定义解析产物）。
 *
 * @param id        边标识（图内唯一）
 * @param source    源节点 ID
 * @param target    目标节点 ID
 * @param condition 条件标记（分支节点出边为 TRUE/FALSE，其余为 ALWAYS）
 */
public record WorkflowEdge(String id, String source, String target, EdgeCondition condition) {
}
