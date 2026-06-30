package com.iacross.flowpilot.flow.domain;

import java.util.List;
import java.util.Map;

/**
 * The versioned flow graph stored as JSONB in flow_version.graph.
 * Shape mirrors the React Flow document (ADR-006, Schema §6.1).
 *
 * The engine only reads type + config from nodes and source/target/sourceHandle from edges.
 * position is canvas-only and is stored but ignored at runtime.
 */
public record FlowGraph(
    List<FlowNode> nodes,
    List<FlowEdge> edges
) {
    public FlowNode findNode(String nodeId) {
        return nodes.stream()
            .filter(n -> n.id().equals(nodeId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Node not found in graph: " + nodeId));
    }

    /**
     * First outgoing edge with no sourceHandle (for linear Advance).
     * Returns null when the node has no default outgoing edge (terminal node).
     */
    public String nextNodeId(String fromNodeId) {
        return edges.stream()
            .filter(e -> e.source().equals(fromNodeId) && e.sourceHandle() == null)
            .findFirst()
            .map(FlowEdge::target)
            .orElse(null);
    }

    /**
     * Outgoing edge matching the given sourceHandle (for Branch).
     * Returns null when no edge matches (branch leads to end of graph).
     */
    public String branchNodeId(String fromNodeId, String edgeKey) {
        return edges.stream()
            .filter(e -> e.source().equals(fromNodeId) && edgeKey.equals(e.sourceHandle()))
            .findFirst()
            .map(FlowEdge::target)
            .orElse(null);
    }
}
