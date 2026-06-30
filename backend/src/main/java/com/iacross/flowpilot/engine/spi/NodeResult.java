package com.iacross.flowpilot.engine.spi;

/**
 * Result returned by a NodeExecutor to the FlowEngine interpreter loop (TRD §5.4, §17).
 *
 * Advance       — proceed to the single outgoing edge (linear flow)
 * Branch(key)   — take the outgoing edge whose sourceHandle == key
 * AwaitInput    — yield; wait for the next inbound message from the user
 * AwaitExternal — yield; wait for an external callback (webhooks, timers — P2/P3)
 * End           — terminate the conversation
 */
public sealed interface NodeResult {
    record Advance()              implements NodeResult {}
    record Branch(String edgeKey) implements NodeResult {}
    record AwaitInput()           implements NodeResult {}
    record AwaitExternal()        implements NodeResult {}
    record End()                  implements NodeResult {}
}
