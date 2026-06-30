package com.iacross.flowpilot.engine.executor;

import com.iacross.flowpilot.channel.spi.OutboundMessage;
import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a text message (with optional inline buttons) to the user.
 *
 * Config:
 *   text    — message body; supports {{variableName}} interpolation
 *   buttons — optional list of {label, value} objects
 */
@Component
public class SendMessageNodeExecutor implements NodeExecutor {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    @Override
    public String supportedType() { return "send_message"; }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String text = interpolate(ctx.configString("text"), ctx);
        List<OutboundMessage.Button> buttons = parseButtons(ctx);

        var recipient = ctx.inboundMessage() != null
            ? ctx.inboundMessage().identity()
            : null;

        if (recipient == null) {
            // No recipient — skip outbound (should not happen in normal flow)
            return new NodeResult.Advance();
        }

        ctx.emitMessage(new OutboundMessage(recipient, text, buttons, ctx.nodeId()));
        return new NodeResult.Advance();
    }

    private String interpolate(String template, NodeContext ctx) {
        if (template == null) return "";
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String value = ctx.getVariable(varName);
            m.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value) : "");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<OutboundMessage.Button> parseButtons(NodeContext ctx) {
        Object raw = ctx.configValue("buttons");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<OutboundMessage.Button> buttons = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String label = String.valueOf(map.get("label"));
                String value = String.valueOf(map.get("value"));
                buttons.add(new OutboundMessage.Button(label, value));
            }
        }
        return buttons.isEmpty() ? null : buttons;
    }
}
