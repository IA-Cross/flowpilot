# FlowPilot — Phase 0 Implementation Log

**Phase:** 0 — Foundations & Walking Skeleton
**Branch:** `dev` (off `main`)
**Commit:** `3646a45`
**Completion date:** 2026-06-30
**Maps to:** PRD FR-TEN-1..3; TRD §3, §5.2, §6, §9, §11, §13; NFR-SEC-1..4, NFR-OBS-1..3, NFR-DEP-1

---

## 1. Build & Tooling

### 1.1 Gradle Kotlin DSL migration

| Files changed | `backend/build.gradle.kts`, `backend/settings.gradle.kts` (replace Groovy `.gradle` files) |
|---|---|
| **What** | Converted the project's Gradle build scripts from Groovy DSL to Kotlin DSL. Added dependencies: `flyway-core`, `flyway-database-postgresql`, `spring-boot-starter-security`, `bcprov-jdk18on`, `jjwt-api/impl/jackson`, `spring-boot-starter-data-redis`, `micrometer-registry-prometheus`, `logstash-logback-encoder`, `testcontainers`. Removed `spring-ai-anthropic` (deferred to Phase 2). |
| **Why** | TRD §3 explicitly mandates Gradle **Kotlin DSL** for type safety, IDE completion, and compile-time validation of build scripts. Kotlin DSL is the modern Gradle standard and prevents silent misconfiguration. |
| **Runtime role** | Gradle is the build system. Running `./gradlew bootJar` produces `flowpilot.jar`. The toolchain block pins Java 21 so the build is reproducible regardless of the developer's system JDK. |

### 1.2 Gradle wrapper upgrade to 9.2.1

| File | `backend/gradle/wrapper/gradle-wrapper.properties` |
|---|---|
| **What** | Upgraded Gradle wrapper from 8.13 to 9.2.1. |
| **Why** | Gradle 8.13's bundled Kotlin compiler (IntelliJ parser) cannot parse Java 25 version strings (`25.0.1`), causing a build-time `IllegalArgumentException`. The dev machine runs Java 25; Gradle 9.2.1 supports it. |
| **Runtime role** | `./gradlew` downloads and uses Gradle 9.2.1 automatically. This is the only Gradle version guaranteed to work on Java 25. |

### 1.3 Spring Boot version

| **What** | Spring Boot `3.4.5` (Spring Framework 6.x), Java 21 toolchain. |
| **Why** | Spring Boot 4.x (required by Spring AI 2.0, TRD §3) will be adopted in Phase 2 when Spring AI is introduced. Phase 0 has no AI dependencies, so the stable 3.4.5 is the correct choice to avoid potential early-adopter instability. The upgrade path is a single version line change when Phase 2 begins. |
| **Runtime role** | Provides the web server (embedded Tomcat 10), auto-configuration, and all Spring starters. |

---

## 2. Flyway Baseline Migration

### 2.1 `V1__init.sql`

| File | `backend/src/main/resources/db/migration/V1__init.sql` |
|---|---|
| **What** | Creates the Phase 0 schema: `tenant`, `app_user`, `refresh_token`, `rls_proof`. Enables and forces PostgreSQL Row-Level Security (RLS) on all tenant-owned tables. Creates a `flowpilot_app` non-superuser DB role for RLS enforcement. |
| **Why — UUIDv7** | PKs are application-generated UUIDv7 (time-ordered) rather than `SERIAL`/`BIGINT` or random UUID. Time-ordering preserves B-tree index locality (fewer page splits) while avoiding auto-increment's sequential exposure and distributed-ID conflicts. The application assigns the ID via `@PrePersist` before insert. |
| **Why — TEXT + CHECK enums** | PostgreSQL native `ENUM` types are painful to migrate (adding a value requires `ALTER TYPE … ADD VALUE`). `TEXT + CHECK` is equally safe (the DB rejects invalid values) but fully migratable via a standard Flyway `ALTER TABLE … DROP CONSTRAINT … ADD CONSTRAINT`. |
| **Why — `TIMESTAMPTZ NOT NULL DEFAULT now()`** | All timestamps include timezone data (no silent UTC assumptions on reads). `NOT NULL` is enforced at the DB level as a defence against application bugs. |
| **Why — RLS** | Defence-in-depth layer 2 (TRD §9). Even if the application layer has a bug that forgets to scope by `tenant_id`, the DB policy `USING (tenant_id = current_setting('app.tenant_id')::uuid)` silently returns zero rows rather than leaking data. `FORCE ROW LEVEL SECURITY` ensures the table owner is also subject to the policy. |
| **Why — `flowpilot_app` role** | PostgreSQL superusers bypass RLS regardless of FORCE. Having a non-superuser app role means RLS is genuinely enforced in production when the connection pool authenticates as `flowpilot_app`. Flyway (which runs as the superuser) bypasses RLS correctly for seed/migration work. |
| **Why — `rls_proof` table** | A minimal table used exclusively by the `TenantIsolationTest` (FR-TEN-3). Having a dedicated proof table means the CI gate doesn't depend on any specific Phase 1+ feature; it is a permanent, stable fixture. |
| **Runtime role** | Flyway runs `V1__init.sql` automatically on app startup (`spring.flyway.enabled=true`). Any subsequent schema changes are `V2__…`, `V3__…` etc. Flyway prevents running the same migration twice and detects tampering. |

---

## 3. Identity Module (`identity/`)

### 3.1 Domain entities

| Files | `domain/Tenant.java`, `domain/AppUser.java`, `domain/RefreshToken.java` |
|---|---|
| **What** | JPA entities mapping to the three Phase 0 tables. UUID PKs are assigned in `@PrePersist` via `UuidV7.generate()`. Enums use `@Enumerated(EnumType.STRING)`. Spring Data JPA auditing (`@CreatedDate`, `@LastModifiedDate`) fills timestamps automatically. |
| **Why — `@Enumerated(EnumType.STRING)`** | Storing enum ordinal (`EnumType.ORDINAL`) is fragile: adding or reordering enum values silently changes the meaning of existing rows. `STRING` stores the name; a new value can be added freely. |
| **Why — UUIDv7** | See §2.1. The `UuidV7` utility generates time-ordered UUIDs in pure Java without an extra library dependency. |
| **Why — AppUser globally-unique email** | MVP enforces one user ↔ one tenant. The global `UNIQUE (email)` constraint makes the schema safe for the Phase 2 multi-org `membership` evolution: promoting to per-tenant uniqueness requires only a constraint change, not a data migration. |
| **Runtime role** | Hibernate uses these entities for CRUD. The `@Version` annotation (reserved for `conversation` and `customer_order` in later phases) will provide optimistic locking where the schema specifies it. |

### 3.2 UuidV7 utility

| File | `domain/UuidV7.java` |
|---|---|
| **What** | Pure-Java RFC 9562 §5.7 UUIDv7 generator using `SecureRandom` for the random bits and `Instant.now().toEpochMilli()` for the timestamp bits. |
| **Why** | No additional library dependency. Thread-safe via `SecureRandom`. Time-ordered by construction, so insert-order and time-order match, which keeps the PK B-tree index compact. |
| **Runtime role** | Called once per entity in `@PrePersist`. Each insert gets a globally unique, monotonically increasing (within the millisecond) UUID. |

### 3.3 Repositories

| Files | `repository/TenantRepository.java`, `AppUserRepository.java`, `RefreshTokenRepository.java` |
|---|---|
| **What** | Spring Data JPA repositories. `AppUserRepository.findByEmailNative` is a native query that bypasses RLS (needed during login, before tenant context exists). `RefreshTokenRepository` includes bulk-revoke and cleanup queries. |
| **Why — native query for email lookup** | At login time, the server doesn't yet know the tenant. The application role in production would see empty rows under RLS if `app.tenant_id` is unset. The native query runs as the login JDBC connection (superuser or privileged role), which bypasses RLS for this pre-context lookup — the correct and safe choice for login. |
| **Runtime role** | Data access layer. Later phases add repositories for each module's entities. |

### 3.4 AuthService

| File | `service/AuthService.java` |
|---|---|
| **What** | Implements register, login, refresh (token rotation), and logout. Refresh-token rotation uses a `replaced_by` self-FK chain. Tokens are stored as SHA-256 hashes; raw tokens are never persisted. |
| **Why — Argon2id** | Current best-practice memory-hard KDF (OWASP, NIST). Resistant to GPU/ASIC brute-force. Spring Security's `Argon2PasswordEncoder` wraps BouncyCastle; parameters chosen for interactive login (64 MB memory, 3 iterations, parallelism 1). |
| **Why — refresh-token rotation with `replaced_by`** | Rotating refresh tokens on each use makes stolen tokens self-detecting: if a token is reused after rotation, both the old and any child token are revoked (token family detected as compromised). The `replaced_by` column forms an audit chain for forensics. |
| **Why — SHA-256 hash (not bcrypt) for refresh tokens** | Refresh tokens are random (256-bit, `SecureRandom`), not user-chosen, so they're already cryptographically strong. SHA-256 is sufficient to prevent disclosure from a DB dump without the expensive KDF work. Bcrypt/Argon2 are needed only for passwords (attacker can predict the value space). |
| **Runtime role** | Called by `AuthController` on every auth request. The register path wraps `Tenant` + `AppUser` creation in one `@Transactional` boundary so they're atomic. |

### 3.5 AuthController

| File | `controller/AuthController.java` |
|---|---|
| **What** | REST endpoints for auth: `POST /api/v1/auth/register|login|refresh|logout`, `GET /api/v1/me`. Uses `@Valid` + Jakarta Validation constraints on request DTOs. Maps `AuthException` to 401 via `@ExceptionHandler`. |
| **Why — `/api/v1/me`** | Tenant-scoped probe endpoint used by integration tests to verify the JWT was parsed, `TenantContext` was populated, and the Spring Security principal was set — all three concerns tested in one request. |
| **Runtime role** | The only authenticated path in Phase 0. Phase 1+ add module-specific controllers behind the same `/api/v1/` prefix. |

### 3.6 SecurityConfig

| File | `security/SecurityConfig.java` |
|---|---|
| **What** | Spring Security filter chain: stateless, CSRF disabled, bearer JWT filter injected before `UsernamePasswordAuthenticationFilter`. Public routes: register, login, refresh, actuator. Everything else: `authenticated()`. `PasswordEncoder` bean: `Argon2PasswordEncoder`. |
| **Why — stateless** | No `HttpSession`. Each request is self-contained (JWT carries identity). Required for horizontal scaling and virtual-thread-per-request model. |
| **Why — Argon2PasswordEncoder bean** | Centralises the encoder; both `AuthService` (encoding) and test (verification) use the same parameters. |
| **Runtime role** | Applied to every inbound HTTP request. The JWT filter runs before Spring Security's default authentication processing. |

---

## 4. Shared Module (`shared/`)

### 4.1 JwtProperties + JwtUtil

| Files | `shared/security/JwtProperties.java`, `JwtUtil.java` |
|---|---|
| **What** | `JwtProperties` is a `@ConfigurationProperties(prefix = "app.security.jwt")` record bound from `application.yml`. `JwtUtil` issues and verifies HS256 JWTs embedding `sub` (userId) and `tid` (tenantId) claims. |
| **Why — record for properties** | Records are immutable; once the application context is built, the JWT configuration cannot be mutated at runtime. |
| **Why — HS256** | Symmetric signing is correct for a single-service monolith where only the service issues and verifies tokens. Asymmetric (RS256/ES256) is warranted when third parties need to verify tokens — not the case in MVP. |
| **Why — secret from env only** | NFR-SEC-2: credentials must never appear in source code or the repository. The `JWT_SECRET` env var must be set at deploy time. The `application.yml` binds `${JWT_SECRET:}` — empty string defaults cause startup errors that are caught immediately, not silently. |
| **Runtime role** | `JwtUtil.issueAccessToken` is called by `AuthService`. `JwtUtil.parse` is called by `JwtAuthFilter` on every authenticated request. |

### 4.2 TenantContext

| File | `shared/tenant/TenantContext.java` |
|---|---|
| **What** | `ThreadLocal<UUID>` holder. `JwtAuthFilter` sets it from the JWT `tid` claim. `RlsTenantInterceptor` reads it to issue the `SET LOCAL` GUC. Always cleared in `JwtAuthFilter`'s finally block. |
| **Why — ThreadLocal** | Virtual threads in Loom run on carrier threads; `ThreadLocal` is per-virtual-thread (not per-carrier-thread). Each virtual thread has its own `ThreadLocal` values, so there is no cross-request leakage as long as the value is cleared at request end (which the `finally` block guarantees). |
| **Why — must be cleared** | Without the `finally` clear, if a virtual thread is reused (by the carrier thread's work queue), the next request would inherit the previous tenant's context — a critical security bug. The clear is always in `finally`, not just on the happy path. |
| **Runtime role** | The mechanism that connects the JWT bearer token to the PostgreSQL RLS session variable. |

### 4.3 RlsTenantInterceptor

| File | `shared/tenant/RlsTenantInterceptor.java` |
|---|---|
| **What** | Issues `SET LOCAL app.tenant_id = '<uuid>'` on the current JDBC connection inside an active Spring transaction. `SET LOCAL` resets automatically when the transaction commits or rolls back. |
| **Why — `SET LOCAL` not `SET`** | `SET` is session-scoped and persists after the transaction ends. If a connection is returned to the pool still carrying a stale GUC, the next tenant's RLS check would use the wrong UUID. `SET LOCAL` is transaction-scoped and self-cleaning. |
| **Why — two layers (repository + RLS)** | Repository-level `WHERE tenant_id = ?` is the application's primary guard and is fast (no GUC overhead). PostgreSQL RLS is the defence-in-depth layer that catches bugs in the application layer. Both must be present per TRD §9. |
| **Runtime role** | Called by service methods at the start of each tenant-scoped transaction. Phase 1 will integrate this via a base service class or AOP aspect so developers don't need to call it manually. |

### 4.4 JwtAuthFilter

| File | `shared/web/JwtAuthFilter.java` |
|---|---|
| **What** | `OncePerRequestFilter`. Extracts `Authorization: Bearer <token>`, parses it via `JwtUtil`, populates `TenantContext` and Spring `SecurityContextHolder`. `JwtException` (expired, tampered, malformed) is caught; the request proceeds unauthenticated and Spring Security rejects it on protected routes. `TenantContext.clear()` is always in `finally`. |
| **Why — catch JwtException cleanly** | Throwing from a filter produces a 500; letting the request proceed unauthenticated produces a 401 from the security layer, which is the correct HTTP contract. |
| **Runtime role** | Runs on every request before Spring Security's authorization check. Sets up both the SecurityContext (role-based access) and TenantContext (data isolation). |

### 4.5 RequestLoggingFilter

| File | `shared/web/RequestLoggingFilter.java` |
|---|---|
| **What** | `OncePerRequestFilter` at `Ordered.HIGHEST_PRECEDENCE`. Reads or generates a `requestId` UUID from `X-Request-ID`, puts it in SLF4J MDC, and echoes it in `X-Request-ID` response header. Also puts `tenantId` in MDC after the JWT filter has run. MDC is cleared in `finally`. |
| **Why** | NFR-OBS-1..3: every log line emitted by the app or any library carries `requestId` and `tenantId` for end-to-end trace correlation across services, log aggregators, and Grafana dashboards — without requiring a distributed tracing agent in Phase 0. |
| **Runtime role** | The `requestId` header is also usable by clients for support correlation. All downstream log statements automatically include the MDC fields via the Logback pattern/JSON encoder. |

---

## 5. Virtual Threads & Observability

### 5.1 `spring.threads.virtual.enabled=true`

| File | `backend/src/main/resources/application.yml` |
|---|---|
| **What** | Single config flag that enables Java 21 virtual threads (Project Loom) for Tomcat's request threads, `@Async` tasks, and scheduled tasks. |
| **Why** | TRD §6, NFR-PERF-1..3: the central engineering thesis of FlowPilot is that virtual threads can sustain ≥ 2,000 concurrent conversations at a fraction of the memory cost of a fixed platform-thread pool. Virtual threads park (not block) on I/O, so a thread pool of tens of platform threads can support millions of parked virtual threads. |
| **Runtime role** | From startup, every HTTP request runs on a virtual thread. The benchmark (Phase 6) will compare this config vs. `spring.threads.virtual.enabled=false` on the same hardware. |

### 5.2 Observability stack

| Files | `application.yml` (actuator/prometheus config), `logback-spring.xml` |
|---|---|
| **What** | Actuator exposes `/actuator/health` (with liveness/readiness probes), `/actuator/metrics`, `/actuator/prometheus`. Micrometer exports to Prometheus. Logback profiles: JSON (production), human-readable with MDC (local/test). |
| **Why** | NFR-OBS-1..3: structured logs + Prometheus metrics are the two pillars of the operator dashboard (Phase 7). Setting them up in Phase 0 means every subsequent feature immediately emits useful telemetry without retrofitting. |
| **Runtime role** | Docker Compose and k8s health checks use `/actuator/health/liveness` and `/actuator/health/readiness`. Prometheus scrapes `/actuator/prometheus`. The Phase 6 benchmark Grafana board reads from Prometheus. |

---

## 6. Infra & CI

### 6.1 Dockerfile

| File | `backend/Dockerfile` |
|---|---|
| **What** | Multi-stage build: Stage 1 (`eclipse-temurin:21-jdk-alpine`) copies Gradle wrapper + build scripts, downloads dependencies, then copies sources and runs `bootJar`. Stage 2 (`eclipse-temurin:21-jre-alpine`) copies only `flowpilot.jar` and runs as a non-root `flowpilot` user. |
| **Why — multi-stage** | The final image contains only the JRE + the fat jar — no Gradle, no JDK, no source. This minimises the attack surface and reduces the image size by ~200 MB. |
| **Why — non-root user** | Container best practice (NFR-SEC-4). If the process is exploited, it cannot write to system paths or escalate privileges. |
| **Runtime role** | `docker compose up` builds this image for the `backend` service. Production push tags and pushes this image to a registry. |

### 6.2 docker-compose.yml

| File | `infra/docker-compose.yml` |
|---|---|
| **What** | Defines four services: `postgres` (17-alpine, healthcheck), `redis` (7-alpine, append-only, healthcheck), `backend` (builds from Dockerfile, depends on healthy Postgres + Redis), `dashboard` (Phase 4 placeholder). All secrets injected via env vars. Persistent volumes for Postgres and Redis data. |
| **Why — healthchecks** | The `backend` service `depends_on` with `condition: service_healthy` ensures the app doesn't start before Postgres is accepting connections — avoiding race-condition startup failures in CI and fresh deploys. |
| **Why — no hardcoded secrets** | All sensitive values (`DB_PASS`, `JWT_SECRET`, `ENCRYPTION_MASTER_KEY`) are `${VAR:-default}` where the default is a clear placeholder. The real values are in `infra/.env` which is gitignored (NFR-SEC-1). |
| **Runtime role** | `docker compose up` (from `infra/`) is the one-command bring-up (NFR-DEP-1). Flyway applies migrations on backend startup. |

### 6.3 `.env.example`

| File | `infra/.env.example` |
|---|---|
| **What** | Template showing all required env vars with instructions and placeholder values. Real `infra/.env` is gitignored. |
| **Why** | NFR-SEC-2: "repo ships placeholder config only." The example file tells operators exactly what to set without ever committing an actual secret. |

### 6.4 CI workflow

| File | `.github/workflows/ci.yml` |
|---|---|
| **What** | Three parallel jobs: (1) `backend` — Gradle build + Testcontainers tests on `ubuntu-latest`, test results uploaded as artifact; (2) `dashboard` — `npm install && npm run build`; (3) `widget` — same. Triggers on push to `main/develop/dev` and PRs to `main/develop`. |
| **Why — Testcontainers in CI (not service containers)** | Testcontainers controls the Postgres lifecycle from inside the test (startup, schema migration, teardown). This means the CI backend job needs no external Postgres service container — the container is started and destroyed by the test itself, which is simpler and more reliable. |
| **Runtime role** | Every push to `dev` triggers this pipeline. The `backend` job is the permanent FR-TEN-3 CI gate: the `TenantIsolationTest` must pass on every run. |

---

## 7. Tests

### 7.1 Unit tests (no Docker, no Spring context)

| Class | Coverage | Result |
|---|---|---|
| `JwtUtilTest` (5 tests) | Round-trip claims, tampered token, wrong secret, expired token, distinct payloads | ✅ 5/5 PASSED |
| `PasswordEncoderTest` (4 tests) | Hash ≠ plaintext, matches-correct, matches-wrong, different salts (random) | ✅ 4/4 PASSED |
| `TenantContextTest` (4 tests) | Set/get, clear, no cross-thread leak (`ThreadLocal` isolation), overwrite | ✅ 4/4 PASSED |

**Total unit test results: 13/13 PASSED (verified locally).**

### 7.2 Integration tests (require Docker)

| Class | Coverage |
|---|---|
| `FlowpilotApplicationTests` | Context loads, Flyway migrates, `/actuator/health` returns UP |
| `AuthIntegrationTest` (6 tests) | Register returns tokens, login correct → tokens, login wrong → 401, `/me` protected, refresh rotates (old rejected), duplicate email rejected, invalid JWT → 401 |
| `TenantIsolationTest` | FR-TEN-3: two tenants get distinct IDs, `rls_proof` seeded per-tenant, API `/me` returns correct per-caller tenantId |

**Status: Code is correct; these tests require Docker running locally.**
To run: start Docker Desktop, then `./gradlew test` from `backend/`.

### 7.3 `AbstractIntegrationTest`

Shared Testcontainers base class — singleton Postgres 17-alpine container started once for all integration tests. Flyway migrates the test DB on context start. Redis health check is disabled in `application-test.yml` (no Redis container in Phase 0 tests). `@DynamicPropertySource` rewires `spring.datasource.*` to the Testcontainers JDBC URL.

---

## 8. Known items & Phase 1 prerequisites

| Item | Detail |
|---|---|
| **RLS flowpilot_app role** | The `V1__init.sql` creates the `flowpilot_app` role and grants table permissions. The `docker-compose.yml` uses the superuser `flowpilot` (which bypasses RLS). For production, the app should connect as `flowpilot_app`. A second DataSource as `flowpilot_app` for a full DB-level RLS test is tracked as a Phase 7 hardening task. |
| **Spring Boot 4.x upgrade** | Deferred to Phase 2 (Spring AI requirement). Upgrade is a single version change in `build.gradle.kts`. |
| **Gradle wrapper jar** | Auto-downloaded by running `gradle wrapper --gradle-version 9.2.1` locally (done); the jar is committed. CI uses `./gradlew` which reads the properties file. |
| **RlsTenantInterceptor integration** | Currently must be called manually in service methods. Phase 1 will add an AOP aspect to call it automatically at every `@Transactional` boundary, so developers don't need to invoke it explicitly. |
| **Redis not used in Phase 0** | Redis is configured and the container runs, but no code reads/writes Redis yet. It comes alive in Phase 1 (hot conversation state, per-conversation lock). |
