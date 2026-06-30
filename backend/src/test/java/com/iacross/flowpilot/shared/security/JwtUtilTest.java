package com.iacross.flowpilot.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for JwtUtil — no Spring context, no DB.
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-at-least-256-bits-long-padding-here!!";
    private static final long ACCESS_EXPIRY_MS  = 900_000L;  // 15 min
    private static final long REFRESH_EXPIRY_MS = 604_800_000L;

    private JwtUtil jwtUtil;
    private UUID userId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        var props = new JwtProperties(SECRET, ACCESS_EXPIRY_MS, REFRESH_EXPIRY_MS);
        jwtUtil = new JwtUtil(props);
        userId   = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("issue → parse → claims round-trip")
    void roundTrip() {
        String token = jwtUtil.issueAccessToken(userId, tenantId);
        Claims claims = jwtUtil.parse(token);

        assertThat(jwtUtil.extractUserId(claims)).isEqualTo(userId);
        assertThat(jwtUtil.extractTenantId(claims)).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("tampered token is rejected")
    void tamperedToken() {
        String token = jwtUtil.issueAccessToken(userId, tenantId);
        // flip one character in the signature part
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThatThrownBy(() -> jwtUtil.parse(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("token signed with a different secret is rejected")
    void wrongSecret() {
        var otherProps = new JwtProperties("completely-different-secret-at-least-256-bits!!", ACCESS_EXPIRY_MS, REFRESH_EXPIRY_MS);
        JwtUtil other = new JwtUtil(otherProps);
        String token = other.issueAccessToken(userId, tenantId);

        assertThatThrownBy(() -> jwtUtil.parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("expired token is rejected")
    void expiredToken() throws InterruptedException {
        // Issue with 1 ms expiry
        var shortProps = new JwtProperties(SECRET, 1L, REFRESH_EXPIRY_MS);
        JwtUtil shortJwt = new JwtUtil(shortProps);
        String token = shortJwt.issueAccessToken(userId, tenantId);

        Thread.sleep(50); // let it expire
        assertThatThrownBy(() -> jwtUtil.parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("different userId and tenantId produce different tokens")
    void differentPayloads() {
        String t1 = jwtUtil.issueAccessToken(UUID.randomUUID(), UUID.randomUUID());
        String t2 = jwtUtil.issueAccessToken(UUID.randomUUID(), UUID.randomUUID());
        assertThat(t1).isNotEqualTo(t2);
    }
}
