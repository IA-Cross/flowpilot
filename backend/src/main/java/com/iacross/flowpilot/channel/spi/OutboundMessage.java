package com.iacross.flowpilot.channel.spi;

import java.util.List;

/**
 * Normalized outbound message that a ChannelAdapter renders to channel-native format.
 * buttons is null/empty for plain text messages.
 */
public record OutboundMessage(
    ChannelIdentity recipient,
    String text,
    List<Button> buttons,
    String producedByNodeId
) {
    public record Button(String label, String value) {}

    public boolean hasButtons() {
        return buttons != null && !buttons.isEmpty();
    }
}
