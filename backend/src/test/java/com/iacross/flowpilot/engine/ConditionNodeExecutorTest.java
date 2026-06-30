package com.iacross.flowpilot.engine;

import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.engine.executor.ConditionNodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeResult;
import com.iacross.flowpilot.flow.domain.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ConditionNodeExecutorTest {

    private final ConditionNodeExecutor executor = new ConditionNodeExecutor();

    @Test
    void supportedType() {
        assertThat(executor.supportedType()).isEqualTo("condition");
    }

    @Test
    void notEmpty_whenValuePresent_branchesTrue() {
        NodeResult result = run(Map.of("name", "Alice"), "name", "not_empty", null, "yes", "no");
        assertThat(result).isInstanceOf(NodeResult.Branch.class);
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("yes");
    }

    @Test
    void notEmpty_whenValueBlank_branchesFalse() {
        NodeResult result = run(Map.of("name", ""), "name", "not_empty", null, "yes", "no");
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("no");
    }

    @Test
    void isEmpty_whenNull_branchesTrue() {
        NodeResult result = run(Collections.emptyMap(), "missing", "is_empty", null, "yes", "no");
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("yes");
    }

    @Test
    void eq_match() {
        NodeResult result = run(Map.of("color", "blue"), "color", "eq", "blue", "yes", "no");
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("yes");
    }

    @Test
    void eq_noMatch() {
        NodeResult result = run(Map.of("color", "red"), "color", "eq", "blue", "yes", "no");
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("no");
    }

    @Test
    void ne_whenDifferent_branchesTrue() {
        NodeResult result = run(Map.of("x", "a"), "x", "ne", "b", "yes", "no");
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("yes");
    }

    @Test
    void contains_whenSubstring_branchesTrue() {
        NodeResult result = run(Map.of("msg", "Hello World"), "msg", "contains", "World", "yes", "no");
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("yes");
    }

    @Test
    void startsWith_match() {
        NodeResult result = run(Map.of("val", "prefix_rest"), "val", "starts_with", "prefix", "yes", "no");
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("yes");
    }

    @Test
    void unknownOperator_branchesFalse() {
        NodeResult result = run(Map.of("v", "x"), "v", "unknown_op", "x", "yes", "no");
        assertThat(((NodeResult.Branch) result).edgeKey()).isEqualTo("no");
    }

    // -----------------------------------------------------------------------

    private NodeResult run(Map<String, Object> vars, String variable, String operator,
                           String value, String trueEdge, String falseEdge) {
        var config = new HashMap<String, Object>();
        config.put("variable", variable);
        config.put("operator", operator);
        if (value != null) config.put("value", value);
        config.put("trueEdge", trueEdge);
        config.put("falseEdge", falseEdge);

        var node = new FlowNode("n1", "condition", config, Map.of());
        var graph = new FlowGraph(List.of(node), List.of());
        var conv = conversation(vars);
        return executor.execute(new NodeContext("n1", graph, null, conv, new ArrayList<>()));
    }

    private Conversation conversation(Map<String, Object> vars) {
        var conv = new Conversation();
        conv.setVariables(new HashMap<>(vars));
        conv.setStatus("active");
        return conv;
    }
}
