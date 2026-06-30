package com.iacross.flowpilot.engine;

import com.iacross.flowpilot.channel.spi.ChannelIdentity;
import com.iacross.flowpilot.channel.spi.ChannelType;
import com.iacross.flowpilot.channel.spi.InboundMessage;
import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.engine.executor.CollectInputNodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeResult;
import com.iacross.flowpilot.flow.domain.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CollectInputNodeExecutorTest {

    private final CollectInputNodeExecutor executor = new CollectInputNodeExecutor();

    @Test
    void supportedType() {
        assertThat(executor.supportedType()).isEqualTo("collect_input");
    }

    @Test
    void firstPass_returnsAwaitInput() {
        // Conversation status is 'active' — first visit, no user input yet
        Conversation conv = conversation("active", new HashMap<>());
        NodeResult result = run(conv, "myVar", null);
        assertThat(result).isInstanceOf(NodeResult.AwaitInput.class);
    }

    @Test
    void secondPass_withInput_storesVariableAndAdvances() {
        // Conversation is 'awaiting_input' and user sends "Alice"
        Conversation conv = conversation("awaiting_input", new HashMap<>());
        InboundMessage msg = new InboundMessage(
            new ChannelIdentity(ChannelType.TELEGRAM, "123"), "Alice", "tg-msg-1");

        NodeResult result = run(conv, "name", msg);

        assertThat(result).isInstanceOf(NodeResult.Advance.class);
        assertThat(conv.getVariables()).containsEntry("name", "Alice");
    }

    @Test
    void secondPass_withNullVariableName_stillAdvances() {
        Conversation conv = conversation("awaiting_input", new HashMap<>());
        InboundMessage msg = new InboundMessage(
            new ChannelIdentity(ChannelType.TELEGRAM, "123"), "text", null);

        NodeResult result = run(conv, null, msg);
        assertThat(result).isInstanceOf(NodeResult.Advance.class);
    }

    @Test
    void awaitingInput_butNoInboundMessage_returnsAwaitInput() {
        // Edge case: status is awaiting_input but no inbound msg (e.g. external trigger)
        Conversation conv = conversation("awaiting_input", new HashMap<>());
        NodeResult result = run(conv, "v", null);
        assertThat(result).isInstanceOf(NodeResult.AwaitInput.class);
    }

    // -----------------------------------------------------------------------

    private NodeResult run(Conversation conv, String variableName, InboundMessage inbound) {
        Map<String, Object> config = new HashMap<>();
        if (variableName != null) config.put("variableName", variableName);
        var node = new FlowNode("n1", "collect_input", config, Map.of());
        var graph = new FlowGraph(List.of(node), List.of());
        return executor.execute(new NodeContext("n1", graph, inbound, conv, new ArrayList<>()));
    }

    private Conversation conversation(String status, Map<String, Object> vars) {
        var conv = new Conversation();
        conv.setStatus(status);
        conv.setVariables(vars);
        return conv;
    }
}
