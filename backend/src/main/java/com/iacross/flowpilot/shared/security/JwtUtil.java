package com.iacross.flowpilot.shared.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies signed JWTs.
 *
 * Claims layout:
 *   sub    — appUser UUID
 *   tid    — tenant UUID
 *   exp    — expiry
 *
 * Algorithm: HS256 (HMAC-SHA256). Secret injected from {@link JwtProperties}
 * which is bound from env — never committed to source control (NFR-SEC-2).
 */
@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;

    public JwtUtil(JwtProperties props) {
        this.signingKey = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = props.accessTokenExpiryMs();
    }

    /** Issue a signed access token. */
    public String issueAccessToken(UUID userId, UUID tenantId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tid", tenantId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpiryMs)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parse and validate a token. Throws {@link JwtException} subtypes on any failure
     * (expired, tampered, malformed).
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Extract userId claim from a pre-validated token without re-verifying. */
    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /** Extract tenantId claim. */
    public UUID extractTenantId(Claims claims) {
        return UUID.fromString(claims.get("tid", String.class));
    }
}
