package com.iacross.flowpilot.channel.spi;

/**
 * Identifies a user on a specific channel — used to normalize to a Contact
 * (dedup via contact_identity.external_id per tenant+channel).
 */
public record ChannelIdentity(ChannelType channel, String externalId) {}
