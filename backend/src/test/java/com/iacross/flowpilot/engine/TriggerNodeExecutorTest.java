package com.iacross.flowpilot.engine;

import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.engine.executor.TriggerNodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeResult;
import com.iacross.flowpilot.flow.domain.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class TriggerNodeExecutorTest {

    private final TriggerNodeExecutor executor = new TriggerNodeExecutor();

    @Test
    void supportedType() {
        assertThat(executor.supportedType()).isEqualTo("trigger");
    }

    @Test
    void alwaysReturnsAdvance() {
        var node = new FlowNode("t1", "trigger", Map.of(), Map.of());
        var graph = new FlowGraph(List.of(node), List.of());
        var conv = new Conversation();
        conv.setVariables(new HashMap<>());
        conv.setStatus("active");

        NodeResult result = executor.execute(new NodeContext("t1", graph, null, conv, new ArrayList<>()));
        assertThat(result).isInstanceOf(NodeResult.Advance.class);
    }
}
