package com.lingxi.workflow.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工作流图（节点 + 边）：图定义 JSON 的解析产物，是发布校验与 M4-4.8 运行时加载的共同中间表示。
 *
 * <p>解析只保证「结构可读」——节点/边字段形状正确即可，业务规则（成环、分支配对、
 * 必填配置等）由 {@link WorkflowGraphValidator} 在发布时全量校验。</p>
 */
public final class WorkflowGraph {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<WorkflowNode> nodes;
    private final List<WorkflowEdge> edges;

    private WorkflowGraph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        this.nodes = Collections.unmodifiableList(nodes);
        this.edges = Collections.unmodifiableList(edges);
    }

    public List<WorkflowNode> nodes() {
        return nodes;
    }

    public List<WorkflowEdge> edges() {
        return edges;
    }

    /**
     * 解析图定义 JSON。
     *
     * @param json     图定义正文
     * @param maxBytes 允许的最大字节数（UTF-8 编码后）
     * @return 解析后的图
     * @throws IllegalArgumentException 图为空、超限或结构不可解析（消息为可直接展示的中文原因）
     */
    public static WorkflowGraph parse(String json, int maxBytes) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("图定义不能为空");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("图定义过大，单次上限 " + maxBytes + " 字节");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("图定义不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("图定义必须是 JSON 对象");
        }
        return new WorkflowGraph(parseNodes(root), parseEdges(root));
    }

    private static List<WorkflowNode> parseNodes(JsonNode root) {
        JsonNode array = root.get("nodes");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("图定义缺少 nodes 数组");
        }
        List<WorkflowNode> nodes = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("节点必须是 JSON 对象");
            }
            String id = text(item, "id");
            if (id == null) {
                throw new IllegalArgumentException("节点缺少 id");
            }
            NodeType type = NodeType.fromWire(item.path("type").asText(null));
            if (type == null) {
                throw new IllegalArgumentException("节点 " + id + " 的类型非法（仅支持 llm/retrieve/tool/branch）");
            }
            nodes.add(new WorkflowNode(id, type, text(item, "name"), parseConfig(id, item.get("config"))));
        }
        return nodes;
    }

    private static List<WorkflowEdge> parseEdges(JsonNode root) {
        JsonNode array = root.get("edges");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("图定义缺少 edges 数组");
        }
        List<WorkflowEdge> edges = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("连线必须是 JSON 对象");
            }
            String id = text(item, "id");
            String source = text(item, "source");
            String target = text(item, "target");
            if (id == null || source == null || target == null) {
                throw new IllegalArgumentException("连线缺少 id/source/target");
            }
            EdgeCondition condition = EdgeCondition.fromWire(item.path("condition").asText(null));
            if (condition == null) {
                throw new IllegalArgumentException("连线 " + id + " 的 condition 非法（仅支持 always/true/false）");
            }
            edges.add(new WorkflowEdge(id, source, target, condition));
        }
        return edges;
    }

    /** 节点配置：缺省视为空配置对象；显式给出非对象时拒绝。 */
    private static Map<String, Object> parseConfig(String nodeId, JsonNode config) {
        if (config == null || config.isNull()) {
            return Collections.emptyMap();
        }
        if (!config.isObject()) {
            throw new IllegalArgumentException("节点 " + nodeId + " 的 config 必须是 JSON 对象");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : config.properties()) {
            values.put(entry.getKey(), toPlainValue(entry.getValue()));
        }
        return values;
    }

    /** JSON 节点 → 原生值（数字/布尔保留原语义，嵌套结构保留为 Map/List）。 */
    private static Object toPlainValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return MAPPER.convertValue(node, Object.class);
    }

    /** 读取非空文本字段；缺失或全空白时返回 {@code null}。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
