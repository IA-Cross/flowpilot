package com.iacross.flowpilot.flow.domain;

/**
 * A directed edge in the flow graph.
 * sourceHandle — when non-null, this edge is taken only when Branch(sourceHandle) is returned.
 * Null sourceHandle means the default/linear outgoing edge (used by Advance).
 */
public record FlowEdge(
    String id,
    String source,
    String target,
    String sourceHandle
) {}
