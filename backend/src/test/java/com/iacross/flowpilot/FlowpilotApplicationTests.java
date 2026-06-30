package com.iacross.flowpilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load + basic smoke test.
 * Verifies the Spring context starts successfully (all beans wire),
 * Flyway applied the migration, and the health endpoint responds.
 */
@DisplayName("Application context loads")
class FlowpilotApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Spring context loads without errors")
    void contextLoads() {
        // If context fails to load, this test fails before reaching this assertion.
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("Actuator health endpoint is UP")
    void healthEndpointIsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }
}
