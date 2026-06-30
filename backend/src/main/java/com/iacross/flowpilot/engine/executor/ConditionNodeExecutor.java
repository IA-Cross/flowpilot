package com.iacross.flowpilot.engine.executor;

import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeResult;
import org.springframework.stereotype.Component;

/**
 * Evaluates a predicate against conversation.variables and branches accordingly.
 *
 * Config:
 *   variable  — the variable name to check
 *   operator  — not_empty | is_empty | eq | ne | contains | starts_with
 *   value     — comparison value (for eq/ne/contains/starts_with operators)
 *   trueEdge  — sourceHandle to follow when condition is true
 *   falseEdge — sourceHandle to follow when condition is false
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public String supportedType() { return "condition"; }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String variable  = ctx.configString("variable");
        String operator  = ctx.configString("operator");
        String value     = ctx.configString("value");
        String trueEdge  = ctx.configString("trueEdge");
        String falseEdge = ctx.configString("falseEdge");

        String varValue = ctx.getVariable(variable);
        boolean result  = evaluate(operator, varValue, value);

        return new NodeResult.Branch(result ? trueEdge : falseEdge);
    }

    private boolean evaluate(String operator, String varValue, String compareValue) {
        if (operator == null) return false;
        return switch (operator) {
            case "not_empty"   -> varValue != null && !varValue.isBlank();
            case "is_empty"    -> varValue == null || varValue.isBlank();
            case "eq"          -> varValue != null && varValue.equals(compareValue);
            case "ne"          -> varValue == null || !varValue.equals(compareValue);
            case "contains"    -> varValue != null && compareValue != null
                                  && varValue.contains(compareValue);
            case "starts_with" -> varValue != null && compareValue != null
                                  && varValue.startsWith(compareValue);
            default            -> false;
        };
    }
}
