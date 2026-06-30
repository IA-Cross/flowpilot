package com.iacross.flowpilot.flow.domain;

import java.util.Map;

/**
 * A node in the flow graph.
 * type  — one of: trigger, send_message, collect_input, condition, ai_intent, ...
 * config — node-type-specific parameters (e.g. {text, buttons} for send_message)
 * position — canvas layout, ignored by engine
 */
public record FlowNode(
    String id,
    String type,
    Map<String, Object> config,
    Map<String, Object> position
) {}
