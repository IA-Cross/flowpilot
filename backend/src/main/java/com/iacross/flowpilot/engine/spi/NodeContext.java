package com.iacross.flowpilot.engine.spi;

import com.iacross.flowpilot.channel.spi.InboundMessage;
import com.iacross.flowpilot.channel.spi.OutboundMessage;
import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.flow.domain.FlowGraph;
import com.iacross.flowpilot.flow.domain.FlowNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context passed to each NodeExecutor during a single engine step.
 *
 * Executors read from config/variables and emit outbound messages.
 * All state mutations go through setVariable() / emitMessage() so the engine
 * controls persistence after the step completes.
 */
public class NodeContext {

    private final String nodeId;
    private final FlowGraph graph;
    private final InboundMessage inboundMessage;
    private final Conversation conversation;
    private final List<OutboundMessage> pendingMessages;
    // Snapshot of conversation.status at the start of this engine run (before any resets)
    private final boolean wasAwaitingInput;

    public NodeContext(String nodeId,
                       FlowGraph graph,
                       InboundMessage inboundMessage,
                       Conversation conversation,
                       List<OutboundMessage> pendingMessages) {
        this(nodeId, graph, inboundMessage, conversation, pendingMessages,
             "awaiting_input".equals(conversation.getStatus()));
    }

    public NodeContext(String nodeId,
                       FlowGraph graph,
                       InboundMessage inboundMessage,
                       Conversation conversation,
                       List<OutboundMessage> pendingMessages,
                       boolean wasAwaitingInput) {
        this.nodeId = nodeId;
        this.graph = graph;
        this.inboundMessage = inboundMessage;
        this.conversation = conversation;
        this.pendingMessages = pendingMessages;
        this.wasAwaitingInput = wasAwaitingInput;
    }

    public String nodeId() { return nodeId; }

    public FlowNode node() { return graph.findNode(nodeId); }

    /** Node's JSONB config map. Never null (may be empty). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> config() {
        var cfg = node().config();
        return cfg != null ? cfg : Map.of();
    }

    public Object configValue(String key) { return config().get(key); }
    public String configString(String key) {
        var v = configValue(key);
        return v != null ? v.toString() : null;
    }

    /** The inbound message that triggered this engine run. May be null for timer/scheduler runs. */
    public InboundMessage inboundMessage() { return inboundMessage; }

    /** True when the conversation was in awaiting_input at the START of this engine run. */
    public boolean isAwaitingInput() { return wasAwaitingInput; }

    public String getVariable(String name) {
        Object v = conversation.getVariables().get(name);
        return v != null ? v.toString() : null;
    }

    public void setVariable(String name, String value) {
        Map<String, Object> vars = new HashMap<>(conversation.getVariables());
        vars.put(name, value);
        conversation.setVariables(vars);
    }

    public void emitMessage(OutboundMessage message) {
        pendingMessages.add(message);
    }

    public FlowGraph graph() { return graph; }
    public Conversation conversation() { return conversation; }
}
