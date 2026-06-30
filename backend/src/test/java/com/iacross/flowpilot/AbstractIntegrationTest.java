package com.iacross.flowpilot;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests.
 *
 * Starts a single Testcontainers Postgres 17 instance shared across the test suite
 * (singleton pattern via static container). Flyway runs automatically on context startup
 * so tests always run against a fresh, properly migrated schema.
 *
 * Uses the 'test' Spring profile → loads application-test.yml which:
 *   - supplies the deterministic JWT secret
 *   - disables the Redis health check (no Redis in test containers)
 */
/**
 * Tag applied here propagates to all subclasses.
 * The Gradle 'test' task excludes this tag (no Docker needed for ./gradlew build).
 * The 'integrationTest' task includes it (requires Docker).
 * Run locally:  ./gradlew integrationTest
 * Run in CI:    ./gradlew build integrationTest
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("flowpilot_test")
                    .withUsername("flowpilot")
                    .withPassword("flowpilot");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }
}
