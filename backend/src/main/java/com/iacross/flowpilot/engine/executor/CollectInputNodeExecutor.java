package com.iacross.flowpilot.engine.executor;

import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeResult;
import org.springframework.stereotype.Component;

/**
 * Waits for the user to provide a value, then stores it in conversation.variables.
 *
 * Config:
 *   variableName — key under which the user's reply is stored
 *
 * Two-phase execution:
 *   Phase 1 (status != awaiting_input): return AwaitInput → engine sets status=awaiting_input
 *   Phase 2 (status == awaiting_input + inbound message present): store value → Advance
 */
@Component
public class CollectInputNodeExecutor implements NodeExecutor {

    @Override
    public String supportedType() { return "collect_input"; }

    @Override
    public NodeResult execute(NodeContext ctx) {
        if (ctx.isAwaitingInput() && ctx.inboundMessage() != null) {
            String variableName = ctx.configString("variableName");
            if (variableName != null) {
                ctx.setVariable(variableName, ctx.inboundMessage().text());
            }
            return new NodeResult.Advance();
        }
        return new NodeResult.AwaitInput();
    }
}
