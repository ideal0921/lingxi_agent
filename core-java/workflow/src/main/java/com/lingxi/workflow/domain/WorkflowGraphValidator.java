package com.lingxi.workflow.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工作流图校验器：发布前的全量业务校验（纯函数，无框架依赖，便于单测）。
 *
 * <p>校验项：节点/边唯一性、引用完整性、单一入口、无环、分支出边配对、
 * 非分支节点出边条件、以及各节点类型的必填配置。</p>
 */
public final class WorkflowGraphValidator {

    private WorkflowGraphValidator() {
    }

    /**
     * 全量校验图定义。
     *
     * @param graph 待校验的图
     * @return {@link Optional#empty()} 表示通过；否则为可直接展示的中文拒绝原因
     */
    public static Optional<String> validate(WorkflowGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return Optional.of("图不能为空，至少需要一个节点");
        }

        Set<String> nodeIds = new HashSet<>();
        for (WorkflowNode node : graph.nodes()) {
            if (!nodeIds.add(node.id())) {
                return Optional.of("节点 id 重复：" + node.id());
            }
        }

        Map<String, List<WorkflowEdge>> outgoing = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        nodeIds.forEach(id -> {
            outgoing.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        });
        Set<String> edgeIds = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (WorkflowEdge edge : graph.edges()) {
            if (!edgeIds.add(edge.id())) {
                return Optional.of("连线 id 重复：" + edge.id());
            }
            if (edge.source().equals(edge.target())) {
                return Optional.of("连线 " + edge.id() + " 存在自环");
            }
            if (!nodeIds.contains(edge.source())) {
                return Optional.of("连线 " + edge.id() + " 的源节点不存在：" + edge.source());
            }
            if (!nodeIds.contains(edge.target())) {
                return Optional.of("连线 " + edge.id() + " 的目标节点不存在：" + edge.target());
            }
            if (!pairs.add(edge.source() + "->" + edge.target())) {
                return Optional.of("存在重复连线：" + edge.source() + " -> " + edge.target());
            }
            outgoing.get(edge.source()).add(edge);
            inDegree.merge(edge.target(), 1, Integer::sum);
        }

        List<String> entries = nodeIds.stream().filter(id -> inDegree.get(id) == 0).toList();
        if (entries.size() != 1) {
            return Optional.of("入度为 0 的入口节点必须恰好一个，当前为 " + entries.size() + " 个：" + entries);
        }
        if (hasCycle(nodeIds, outgoing, inDegree)) {
            return Optional.of("图存在环，工作流必须为有向无环图");
        }

        for (WorkflowNode node : graph.nodes()) {
            List<WorkflowEdge> outs = outgoing.get(node.id());
            if (node.type() == NodeType.BRANCH) {
                Optional<String> branchError = validateBranchEdges(node, outs);
                if (branchError.isPresent()) {
                    return branchError;
                }
                continue;
            }
            Optional<String> conditionError = outs.stream()
                    .filter(edge -> edge.condition() != EdgeCondition.ALWAYS)
                    .findFirst()
                    .map(edge -> "非分支节点 " + node.id() + " 的出边必须为 always，连线 "
                            + edge.id() + " 为 " + edge.condition().wire());
            if (conditionError.isPresent()) {
                return conditionError;
            }
        }

        for (WorkflowNode node : graph.nodes()) {
            Optional<String> configError = validateNodeConfig(node);
            if (configError.isPresent()) {
                return configError;
            }
        }
        return Optional.empty();
    }

    /** 分支节点必须恰好两条出边，且条件分别为 true / false。 */
    private static Optional<String> validateBranchEdges(WorkflowNode node, List<WorkflowEdge> outs) {
        if (outs.size() != 2) {
            return Optional.of("分支节点 " + node.id() + " 必须恰好有两条出边（true/false），当前为 " + outs.size() + " 条");
        }
        Set<EdgeCondition> conditions = new HashSet<>();
        for (WorkflowEdge edge : outs) {
            conditions.add(edge.condition());
        }
        if (!conditions.equals(EnumSet.of(EdgeCondition.TRUE, EdgeCondition.FALSE))) {
            return Optional.of("分支节点 " + node.id() + " 的两条出边条件必须分别为 true 与 false");
        }
        return Optional.empty();
    }

    /** 各节点类型的必填配置。 */
    private static Optional<String> validateNodeConfig(WorkflowNode node) {
        return switch (node.type()) {
            case LLM -> Optional.empty();
            case RETRIEVE -> requireConfig(node, "kb_id", "知识检索节点必须配置 kb_id");
            case TOOL -> requireConfig(node, "tool_name", "工具节点必须配置 tool_name");
            case BRANCH -> requireConfig(node, "expression", "分支节点必须配置 expression 条件");
        };
    }

    private static Optional<String> requireConfig(WorkflowNode node, String key, String message) {
        if (node.configString(key) == null) {
            return Optional.of("节点 " + node.id() + "：" + message);
        }
        return Optional.empty();
    }

    /** Kahn 拓扑排序：存在未出队节点即判定成环。 */
    private static boolean hasCycle(Set<String> nodeIds, Map<String, List<WorkflowEdge>> outgoing,
                                    Map<String, Integer> inDegree) {
        Map<String, Integer> remaining = new HashMap<>(inDegree);
        Deque<String> queue = new ArrayDeque<>();
        remaining.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (WorkflowEdge edge : outgoing.get(current)) {
                int degree = remaining.merge(edge.target(), -1, Integer::sum);
                if (degree == 0) {
                    queue.add(edge.target());
                }
            }
        }
        return visited != nodeIds.size();
    }
}
