package com.lingxi.workflow.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图定义解析与发布校验测试（纯函数，无外部依赖）。
 *
 * <p>覆盖：结构解析失败路径、唯一性/引用完整性、单一入口、成环、分支出边配对、
 * 非分支出边条件与各类型必填配置。</p>
 */
class WorkflowGraphValidatorTest {

    private static final int MAX_BYTES = 262144;

    /** 合法图：检索 → 分支（true → LLM / false → 工具）。 */
    private static final String VALID_GRAPH = """
            {
              "nodes": [
                {"id":"n1","type":"retrieve","name":"知识检索","config":{"kb_id":"kb-1"}},
                {"id":"n2","type":"branch","name":"置信度分支","config":{"expression":"top_score >= 0.6"}},
                {"id":"n3","type":"llm","name":"生成回答","config":{"temperature":0.7}},
                {"id":"n4","type":"tool","name":"查订单","config":{"tool_name":"get_order"}}
              ],
              "edges": [
                {"id":"e1","source":"n1","target":"n2"},
                {"id":"e2","source":"n2","target":"n3","condition":"true"},
                {"id":"e3","source":"n2","target":"n4","condition":"false"}
              ]
            }
            """;

    /** 解析并校验，返回拒绝原因（通过时为空）。 */
    private static Optional<String> validate(String json) {
        return WorkflowGraphValidator.validate(WorkflowGraph.parse(json, MAX_BYTES));
    }

    // ---------- 解析层（结构可读性） ----------

    @Test
    void parseRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.parse("  ", MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.parse("{not json", MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.parse("[]", MAX_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGraph.parse("{\"edges\":[]}", MAX_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGraph.parse("{\"nodes\":[]}", MAX_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGraph.parse("{\"nodes\":[{\"id\":\"n1\",\"type\":\"unknown\"}],\"edges\":[]}", MAX_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGraph.parse("{\"nodes\":[{\"type\":\"llm\"}],\"edges\":[]}", MAX_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGraph.parse(
                        "{\"nodes\":[{\"id\":\"n1\",\"type\":\"llm\",\"config\":\"x\"}],\"edges\":[]}", MAX_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowGraph.parse(
                        "{\"nodes\":[{\"id\":\"n1\",\"type\":\"llm\"}],"
                                + "\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n1\",\"condition\":\"maybe\"}]}",
                        MAX_BYTES));
    }

    @Test
    void parseRejectsOversizedDefinition() {
        String oversized = "{\"nodes\":[{\"id\":\"n1\",\"type\":\"llm\",\"name\":\""
                + "x".repeat(100) + "\"}],\"edges\":[]}";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> WorkflowGraph.parse(oversized, 16));
        assertTrue(error.getMessage().contains("过大"), error.getMessage());
    }

    /** 未显式标注条件的普通边默认按 always 处理。 */
    @Test
    void parseDefaultsMissingConditionToAlways() {
        WorkflowGraph graph = WorkflowGraph.parse(VALID_GRAPH, MAX_BYTES);
        assertEquals(EdgeCondition.TRUE, graph.edges().get(1).condition());
        assertEquals(EdgeCondition.FALSE, graph.edges().get(2).condition());
    }

    // ---------- 校验层（业务规则） ----------

    @Test
    void validGraphPasses() {
        assertTrue(validate(VALID_GRAPH).isEmpty());
    }

    @Test
    void rejectsEmptyGraph() {
        assertTrue(validate("{\"nodes\":[],\"edges\":[]}").orElseThrow().contains("不能为空"));
    }

    @Test
    void rejectsDuplicateNodeId() {
        String json = """
                {"nodes":[{"id":"n1","type":"llm"},{"id":"n1","type":"llm"}],"edges":[]}
                """;
        assertTrue(validate(json).orElseThrow().contains("节点 id 重复"));
    }

    @Test
    void rejectsDuplicateEdgeIdAndSelfLoopAndDuplicatePair() {
        String duplicateEdgeId = """
                {"nodes":[{"id":"n1","type":"llm"},{"id":"n2","type":"llm"}],
                 "edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e1","source":"n1","target":"n2"}]}
                """;
        assertTrue(validate(duplicateEdgeId).orElseThrow().contains("连线 id 重复"));

        String selfLoop = """
                {"nodes":[{"id":"n1","type":"llm"}],"edges":[{"id":"e1","source":"n1","target":"n1"}]}
                """;
        assertTrue(validate(selfLoop).orElseThrow().contains("自环"));

        String duplicatePair = """
                {"nodes":[{"id":"n1","type":"llm"},{"id":"n2","type":"llm"}],
                 "edges":[{"id":"e1","source":"n1","target":"n2"},{"id":"e2","source":"n1","target":"n2"}]}
                """;
        assertTrue(validate(duplicatePair).orElseThrow().contains("重复连线"));
    }

    @Test
    void rejectsUnknownEdgeEndpoint() {
        String json = """
                {"nodes":[{"id":"n1","type":"llm"}],
                 "edges":[{"id":"e1","source":"n1","target":"n9"}]}
                """;
        assertTrue(validate(json).orElseThrow().contains("目标节点不存在"));
    }

    @Test
    void rejectsMultipleEntries() {
        String json = """
                {"nodes":[{"id":"n1","type":"llm"},{"id":"n2","type":"llm"}],"edges":[]}
                """;
        assertTrue(validate(json).orElseThrow().contains("入口节点必须恰好一个"));
    }

    @Test
    void rejectsCycle() {
        // n1 → n2 → n3 → n1：无入口且成环，先命中入口校验，故显式补一个入口 n4
        String json = """
                {"nodes":[{"id":"n1","type":"llm"},{"id":"n2","type":"llm"},
                          {"id":"n3","type":"llm"},{"id":"n4","type":"llm"}],
                 "edges":[{"id":"e1","source":"n4","target":"n1"},
                          {"id":"e2","source":"n1","target":"n2"},
                          {"id":"e3","source":"n2","target":"n3"},
                          {"id":"e4","source":"n3","target":"n1"}]}
                """;
        assertTrue(validate(json).orElseThrow().contains("存在环"));
    }

    @Test
    void rejectsBranchWithWrongOutEdgeCount() {
        String json = """
                {"nodes":[{"id":"n1","type":"branch","config":{"expression":"x > 1"}},
                          {"id":"n2","type":"llm"}],
                 "edges":[{"id":"e1","source":"n1","target":"n2","condition":"true"}]}
                """;
        assertTrue(validate(json).orElseThrow().contains("恰好有两条出边"));
    }

    @Test
    void rejectsBranchWithUnpairedConditions() {
        String json = """
                {"nodes":[{"id":"n1","type":"branch","config":{"expression":"x > 1"}},
                          {"id":"n2","type":"llm"},{"id":"n3","type":"llm"}],
                 "edges":[{"id":"e1","source":"n1","target":"n2","condition":"true"},
                          {"id":"e2","source":"n1","target":"n3","condition":"true"}]}
                """;
        assertTrue(validate(json).orElseThrow().contains("必须分别为 true 与 false"));
    }

    @Test
    void rejectsConditionOnNonBranchNode() {
        String json = """
                {"nodes":[{"id":"n1","type":"llm"},{"id":"n2","type":"llm"}],
                 "edges":[{"id":"e1","source":"n1","target":"n2","condition":"true"}]}
                """;
        assertTrue(validate(json).orElseThrow().contains("出边必须为 always"));
    }

    @Test
    void rejectsMissingRequiredNodeConfig() {
        String missingKbId = """
                {"nodes":[{"id":"n1","type":"retrieve"}],"edges":[]}
                """;
        assertTrue(validate(missingKbId).orElseThrow().contains("kb_id"));

        String missingToolName = """
                {"nodes":[{"id":"n1","type":"tool"}],"edges":[]}
                """;
        assertTrue(validate(missingToolName).orElseThrow().contains("tool_name"));

        // 分支节点出边配对合法，专测缺失 expression
        String missingExpression = """
                {"nodes":[{"id":"n1","type":"branch"},{"id":"n2","type":"llm"},{"id":"n3","type":"llm"}],
                 "edges":[{"id":"e1","source":"n1","target":"n2","condition":"true"},
                          {"id":"e2","source":"n1","target":"n3","condition":"false"}]}
                """;
        assertTrue(validate(missingExpression).orElseThrow().contains("expression"));
    }

    /** LLM 节点无必填配置，空配置即可发布（模型留空表示走网关降级链）。 */
    @Test
    void llmNodeNeedsNoRequiredConfig() {
        assertTrue(validate("{\"nodes\":[{\"id\":\"n1\",\"type\":\"llm\"}],\"edges\":[]}").isEmpty());
    }
}
