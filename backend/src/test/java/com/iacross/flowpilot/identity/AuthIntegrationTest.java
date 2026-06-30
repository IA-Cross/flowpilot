package com.iacross.flowpilot.identity;

import com.iacross.flowpilot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the auth REST endpoints.
 *
 * Tests the full HTTP stack (Spring MVC → SecurityConfig → Service → DB) against a real
 * Postgres container with Flyway-applied schema.
 */
@DisplayName("Auth endpoints")
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    // --- Helpers ---

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN    = "/api/v1/auth/login";
    private static final String REFRESH  = "/api/v1/auth/refresh";
    private static final String LOGOUT   = "/api/v1/auth/logout";
    private static final String ME       = "/api/v1/me";

    private Map<String, String> register(String email, String password) {
        var body = Map.of(
                "tenantName", "Test Workspace " + email,
                "email",      email,
                "password",   password,
                "displayName","Test User"
        );
        @SuppressWarnings("unchecked")
        Map<String, String> response = restTemplate.postForObject(REGISTER, body, Map.class);
        return response;
    }

    private HttpHeaders bearerHeaders(String accessToken) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    // --- Tests ---

    @Test
    @DisplayName("register creates workspace and returns tokens")
    void registerReturnsTokens() {
        var tokens = register("register@example.com", "secure-pass-123!");
        assertThat(tokens).containsKeys("accessToken", "refreshToken");
        assertThat(tokens.get("accessToken")).isNotBlank();
        assertThat(tokens.get("refreshToken")).isNotBlank();
    }

    @Test
    @DisplayName("login with correct credentials returns tokens")
    void loginWithCorrectCredentials() {
        String email = "login@example.com";
        String pass  = "secure-pass-456!";
        register(email, pass);

        var loginBody = Map.of("email", email, "password", pass);
        @SuppressWarnings("unchecked")
        Map<String, String> tokens = restTemplate.postForObject(LOGIN, loginBody, Map.class);
        assertThat(tokens.get("accessToken")).isNotBlank();
    }

    @Test
    @DisplayName("login with wrong password returns 401")
    void loginWrongPassword() {
        String email = "wrong-pass@example.com";
        register(email, "correct-password-789!");

        var loginBody = Map.of("email", email, "password", "WRONG");
        ResponseEntity<String> response = restTemplate.postForEntity(LOGIN, loginBody, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("protected /me endpoint requires valid bearer token")
    void meRequiresBearerToken() {
        // No auth → 401
        ResponseEntity<String> noAuth = restTemplate.getForEntity(ME, String.class);
        assertThat(noAuth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Valid token → 200
        var tokens = register("me-test@example.com", "pass-me-12345!");
        var entity  = new HttpEntity<>(bearerHeaders(tokens.get("accessToken")));
        ResponseEntity<String> ok = restTemplate.exchange(ME, HttpMethod.GET, entity, String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("refresh issues new tokens; old refresh token is invalidated")
    void refreshRotatesToken() {
        var tokens = register("refresh@example.com", "refresh-pass-99!");
        String oldRefresh = tokens.get("refreshToken");

        var refreshBody = Map.of("refreshToken", oldRefresh);
        @SuppressWarnings("unchecked")
        Map<String, String> newTokens = restTemplate.postForObject(REFRESH, refreshBody, Map.class);
        assertThat(newTokens.get("accessToken")).isNotBlank();
        assertThat(newTokens.get("refreshToken")).isNotBlank();
        assertThat(newTokens.get("refreshToken")).isNotEqualTo(oldRefresh);

        // Old refresh token must now be rejected
        ResponseEntity<String> reuse = restTemplate.postForEntity(REFRESH, refreshBody, String.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("duplicate email registration is rejected")
    void duplicateEmailRejected() {
        String email = "dupe@example.com";
        register(email, "first-password-1!");

        var body = Map.of("tenantName", "Second", "email", email,
                          "password", "second-password-2!", "displayName", "Dupe");
        ResponseEntity<String> response = restTemplate.postForEntity(REGISTER, body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("invalid JWT is rejected with 401")
    void invalidJwtRejected() {
        var headers = new HttpHeaders();
        headers.setBearerAuth("not.a.valid.jwt.token");
        var entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(ME, HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
