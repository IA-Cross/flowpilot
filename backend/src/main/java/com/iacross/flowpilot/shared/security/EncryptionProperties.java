package com.iacross.flowpilot.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.security.encryption.*}.
 * The master key is a hex-encoded 256-bit (32-byte) AES-GCM key.
 * MUST be supplied at runtime via env — never committed.
 */
@ConfigurationProperties(prefix = "app.security.encryption")
public record EncryptionProperties(String masterKey) {}
