package com.iacross.flowpilot.channel.spi;

/**
 * Port for channel adapters — verbatim from TRD §17 (ADR-005).
 *
 * Each channel (Telegram, web widget) implements this interface so the engine
 * and management layer are independent of transport details.
 *
 * verify() — returns false if the request is not from the expected provider.
 * parseInbound() — maps the raw HTTP body to a normalized InboundMessage.
 * identityOf() — extracts the ChannelIdentity for contact normalization.
 * send() — renders and delivers an OutboundMessage via the channel's API.
 */
public interface ChannelAdapter {
    ChannelType type();
    boolean verify(InboundRequest request, String expectedSecret);
    InboundMessage parseInbound(InboundRequest request);
    ChannelIdentity identityOf(InboundMessage message);
    void send(OutboundMessage message, String botToken);
}
