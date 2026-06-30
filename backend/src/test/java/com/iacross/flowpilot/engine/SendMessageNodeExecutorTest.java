package com.iacross.flowpilot.engine;

import com.iacross.flowpilot.channel.spi.ChannelIdentity;
import com.iacross.flowpilot.channel.spi.ChannelType;
import com.iacross.flowpilot.channel.spi.InboundMessage;
import com.iacross.flowpilot.channel.spi.OutboundMessage;
import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.engine.executor.SendMessageNodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeResult;
import com.iacross.flowpilot.flow.domain.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class SendMessageNodeExecutorTest {

    private final SendMessageNodeExecutor executor = new SendMessageNodeExecutor();

    @Test
    void supportedType() {
        assertThat(executor.supportedType()).isEqualTo("send_message");
    }

    @Test
    void simpleText_emitsOutboundAndAdvances() {
        List<OutboundMessage> outbox = new ArrayList<>();
        run("Hello!", Map.of(), null, null, outbox);

        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).text()).isEqualTo("Hello!");
    }

    @Test
    void interpolatesVariables() {
        List<OutboundMessage> outbox = new ArrayList<>();
        run("Hi {{name}}, you are {{age}} years old.", Map.of("name", "Alice", "age", "30"),
            null, null, outbox);

        assertThat(outbox.get(0).text()).isEqualTo("Hi Alice, you are 30 years old.");
    }

    @Test
    void missingVariable_replacedWithEmpty() {
        List<OutboundMessage> outbox = new ArrayList<>();
        run("Hello {{missing}}!", Map.of(), null, null, outbox);
        assertThat(outbox.get(0).text()).isEqualTo("Hello !");
    }

    @Test
    void withButtons_emitsButtonsOnOutbound() {
        List<OutboundMessage> outbox = new ArrayList<>();
        List<Map<String, String>> buttons = List.of(
            Map.of("label", "Yes", "value", "yes"),
            Map.of("label", "No",  "value", "no")
        );
        run("Continue?", Map.of(), buttons, null, outbox);

        assertThat(outbox.get(0).buttons()).hasSize(2);
        assertThat(outbox.get(0).buttons().get(0).label()).isEqualTo("Yes");
    }

    @Test
    void noInboundMessage_skipsOutbound_returnsAdvance() {
        List<OutboundMessage> outbox = new ArrayList<>();
        NodeResult result = runWithResult("text", Map.of(), null, null, outbox);
        // No recipient → no outbound message emitted; still Advance
        assertThat(result).isInstanceOf(NodeResult.Advance.class);
        assertThat(outbox).isEmpty();
    }

    // -----------------------------------------------------------------------

    private void run(String text, Map<String, Object> vars, List<Map<String, String>> buttons,
                     String nodeId, List<OutboundMessage> outbox) {
        InboundMessage inbound = new InboundMessage(
            new ChannelIdentity(ChannelType.TELEGRAM, "tg-123"), "input", "msg-1");
        runWithResult(text, vars, buttons, nodeId, outbox, inbound);
    }

    private NodeResult runWithResult(String text, Map<String, Object> vars,
                                      List<Map<String, String>> buttons, String nodeId,
                                      List<OutboundMessage> outbox) {
        return runWithResult(text, vars, buttons, nodeId, outbox, null);
    }

    private NodeResult runWithResult(String text, Map<String, Object> vars,
                                      List<Map<String, String>> buttons, String nodeId,
                                      List<OutboundMessage> outbox, InboundMessage inbound) {
        Map<String, Object> config = new HashMap<>();
        config.put("text", text);
        if (buttons != null) config.put("buttons", buttons);

        var node = new FlowNode("n1", "send_message", config, Map.of());
        var graph = new FlowGraph(List.of(node), List.of());
        var conv = new Conversation();
        conv.setVariables(new HashMap<>(vars));
        conv.setStatus("active");

        return executor.execute(new NodeContext("n1", graph, inbound, conv, outbox));
    }
}
