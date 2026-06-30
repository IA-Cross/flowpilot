package com.iacross.flowpilot.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.security.jwt.*} in application YAML.
 * Secret MUST be supplied at runtime via env/secret config — never committed.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpiryMs,
        long refreshTokenExpiryMs
) {}
