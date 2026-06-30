package com.iacross.flowpilot.channel.spi;

/**
 * Normalized inbound message from any channel.
 * text  — user's typed message or button payload value
 * extra — optional provider-specific data (e.g. channel_message_id for dedup)
 */
public record InboundMessage(
    ChannelIdentity identity,
    String text,
    String channelMessageId
) {}
