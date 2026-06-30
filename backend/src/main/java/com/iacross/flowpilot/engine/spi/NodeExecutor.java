package com.iacross.flowpilot.engine.spi;

/**
 * SPI for engine node executors (TRD §5.4, §17).
 *
 * Each node type in the flow graph has exactly one NodeExecutor implementation.
 * New node types are added by implementing this interface — the interpreter loop
 * never changes. Spring auto-discovers implementations via @Component.
 */
public interface NodeExecutor {
    /** The node type string (lowercase, matches graph node.type field). */
    String supportedType();
    NodeResult execute(NodeContext ctx);
}
