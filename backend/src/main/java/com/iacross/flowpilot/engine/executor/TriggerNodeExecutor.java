package com.iacross.flowpilot.engine.executor;

import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeResult;
import org.springframework.stereotype.Component;

/**
 * Entry-point node for a new conversation.
 * Fires when a new conversation starts and immediately advances to the next node.
 */
@Component
public class TriggerNodeExecutor implements NodeExecutor {

    @Override
    public String supportedType() { return "trigger"; }

    @Override
    public NodeResult execute(NodeContext ctx) {
        return new NodeResult.Advance();
    }
}
