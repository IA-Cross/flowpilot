# FlowPilot — Phase 1 Implementation Log

**Phase:** 1 — Orchestration Engine + Telegram Channel
**Branch:** `dev` (off `main`, Phase 0)
**Completion date:** 2026-06-30
**Maps to:** PRD FR-CHN-1..4, FR-ENG-1..3, FR-BLD-2 (partial), FR-BLD-4; TRD §5.3, §5.4, §6, §8.1, §17; Backend-Schema §5–§7; App-Flow Part B

---

## 1. Flyway Migration — `V2__channels_flows_engine.sql`

| File | `backend/src/main/resources/db/migration/V2__channels_flows_engine.sql` |
|---|---|
| **What** | Adds all Phase 1 tables: `channel_connection`, `flow`, `flow_version`, `contact`, `contact_identity`, `conversation`, `message`, `conversation_event`. Every tenant-owned table has `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY`, a `tenant_isolation` policy, and `GRANT … TO flowpilot_app`. |
| **Why — Schema §5–§7** | Follows the authoritative Backend-Schema exactly: `BYTEA` for encrypted secrets, JSONB for `graph`/`variables`/`body`, deferred FK `flow.published_version_id → flow_version(id)` (bootstrapping chicken-and-egg). `version BIGINT` on `conversation` supports JPA `@Version` optimistic locking as secondary protection (Redis lock is primary). |
| **Why — TEXT + CHECK** | Same rationale as V1. All enum-like columns use `TEXT + CHECK (value IN (…))` with lowercase values (`'active'`, `'telegram'`, etc.) to match engine/adapter string comparisons. |
| **Why — partial index** | `ix_conv_wait_due ON conversation(wait_until) WHERE status = 'waiting'` keeps the delay-scheduler poll (Phase 2) index tiny; only waiting rows are scanned. |
| **Runtime role** | Flyway applies `V2` at startup after `V1`. The engine, adapter, and contact service depend on all these tables. |

---

## 2. `shared/` Additions

### 2.1 `shared/id/UuidV7.java` (relocated)

| **What** | Moved `UuidV7.generate()` from `identity.domain` to `shared.id`. The `identity.domain.UuidV7` is now a `@Deprecated` shim delegating to the shared version. |
| **Why** | Phase 1 introduces entities in `channel`, `flow`, and `engine` packages — all need UUIDv7 generation. Keeping it in `identity.domain` would create an upward cross-module dependency. |

### 2.2 `shared/security/EncryptionProperties.java` + `AesGcmEncryptor.java`

| **What** | `@ConfigurationProperties("app.security.encryption")` record holding `masterKey` (hex-encoded 32 bytes). `AesGcmEncryptor` encrypts/decrypts with AES-256-GCM: 12-byte random IV prepended to ciphertext+tag. |
| **Why — NFR-SEC-2** | Bot tokens and webhook secrets must never be stored in plaintext. AES-GCM provides authenticated encryption — tampering the ciphertext will fail decryption. Random IV per call means two encryptions of the same value produce distinct ciphertexts. |
| **Runtime role** | `ChannelManagementService` encrypts the bot token and webhook secret at channel connect time. `FlowEngine` decrypts the bot token when sending outbound messages. The webhook controller decrypts the webhook secret for each inbound verification. |

### 2.3 `shared/redis/RedisConfig.java`

| **What** | `StringRedisTemplate` bean with UTF-8 key+value serializers. |
| **Why** | The engine stores conversation state as JSON strings; the lock stores a lock-id string. `StringRedisTemplate` is the lightest Redis client abstraction and avoids unnecessary serialization complexity. |

### 2.4 `shared/lock/ConversationLock.java`

| **What** | Per-conversation distributed lock: `SET conv:lock:{id} {lockId} NX PX 30000` to acquire; Lua `if get(key)==lockId then del(key) end` to release. `executeWithLock(UUID, Supplier)` retries up to 5× with 150 ms delay. |
| **Why — TRD §6.2** | Single-writer guarantee: only one engine invocation advances a conversation at a time. Retry sleeps use `Thread.sleep` which parks the virtual thread, not the carrier thread (no Loom pinning). Lua compare-and-delete prevents a holder from releasing another process's lock on timeout. |
| **Runtime role** | `FlowEngine.processInbound` wraps the entire engine step inside `executeWithLock`. |

### 2.5 `shared/exception/GlobalExceptionHandler.java`

| **What** | `@RestControllerAdvice` mapping `IllegalArgumentException → 400`, `LockAcquisitionException → 503`, `Exception → 500`. |
| **Why** | Converts engine/channel exceptions to structured HTTP responses for the management API. The webhook controller catches exceptions independently (always returns 200 to Telegram). |

---

## 3. Channel Module — Contract + Telegram Adapter

### 3.1 `channel/spi/` — Channel contract (ADR-005)

| **What** | `ChannelAdapter` interface: `type()`, `verify(InboundRequest, secret)`, `parseInbound(InboundRequest)`, `identityOf(InboundMessage)`, `send(OutboundMessage, botToken)`. Value types: `InboundRequest`, `InboundMessage`, `OutboundMessage` (with `Button`), `ChannelIdentity`, `ChannelType`. |
| **Why — ADR-005** | Strict SPI boundary: the engine and webhook controller never import channel-specific code. New channels (web widget, WhatsApp) implement `ChannelAdapter` and are auto-discovered via `@Component` + Spring `List<ChannelAdapter>` injection. |

### 3.2 `channel/telegram/TelegramChannelAdapter.java`

| **What** | Implements `ChannelAdapter`: `verify()` checks `X-Telegram-Bot-Api-Secret-Token` header (constant-time string comparison); `parseInbound()` maps both `message` and `callback_query` Telegram update types to `InboundMessage`; `send()` renders text + inline keyboard via `TelegramApiClient`. |
| **Why** | Telegram sends `callback_query` for button taps — both must be handled so the collect_input node works with buttons. |

### 3.3 `channel/telegram/TelegramApiClient.java`

| **What** | `RestClient`-based HTTP client (base URL from `${app.telegram.base-url}`): `setWebhook(botToken, url, secretToken)`, `sendMessage(botToken, chatId, outbound)` with inline keyboard JSON. |
| **Why — blocking RestClient** | Loom virtual threads make blocking I/O cheap. `RestClient` (blocking) is simpler and more debuggable than `WebClient` (reactive) on a Loom runtime. No carrier thread pinning: RestClient uses NIO internally. |

### 3.4 `channel/telegram/TelegramWebhookController.java`

| **What** | `POST /webhooks/telegram/{channelConnectionId}` (whitelisted `permitAll` in `SecurityConfig`). Thin: privileged pre-RLS lookup → `TenantContext.set()` → verify → parse → `FlowEngine.processInbound()` → return 200. Always returns 200 (Telegram retries on errors). |
| **Why — privileged lookup** | The webhook arrives before we know the tenant. We must look up `channel_connection` by ID (outside RLS) to discover `tenant_id`, then set `TenantContext`. This is the same pattern used by `findByIdForWebhook` native query. |

### 3.5 `channel/management/ChannelManagementService.java` + `ChannelManagementController.java`

| **What** | `POST /api/v1/channels/telegram` — generates random 32-byte webhook secret, encrypts both bot token and webhook secret with AES-GCM, calls Telegram `setWebhook`, persists `channel_connection` (status `connected`). `GET /api/v1/channels` lists channels. |
| **Why** | Bot tokens are submitted once at channel connect and never retrieved in plaintext again. The webhook secret is opaque to the caller; Telegram sends it on every update. |

### 3.6 `channel/contact/ContactService.java`

| **What** | `resolveOrCreate(ChannelIdentity, tenantId, displayName)` — deduplicates on `(tenant_id, channel, external_id)` unique constraint. If a `contact_identity` row exists, returns the linked `contact` and updates `last_seen_at`. Otherwise creates both records atomically. |
| **Why — Schema §7** | Cross-channel normalization: the same Telegram user always maps to the same `contact`. This is the foundation for the unified customer timeline in Phase 3. |

---

## 4. Flow Module — Versioned Flow Persistence

### 4.1 `flow/domain/FlowGraph.java` + `FlowNode.java` + `FlowEdge.java`

| **What** | `FlowGraph(nodes, edges)` record with `findNode(id)`, `nextNodeId(fromId)` (follows edge with null `sourceHandle`), `branchNodeId(fromId, edgeKey)` (follows edge matching `sourceHandle`). Returns `null` for terminal nodes (no outgoing edge). |
| **Why — ADR-006** | The graph mirrors the React Flow document shape stored in `flow_version.graph` (JSONB). `sourceHandle` is the React Flow edge label — branch nodes emit it as the key. Returning `null` (not throwing) from `nextNodeId` lets the engine detect terminal nodes cleanly. |

### 4.2 `flow/domain/FlowVersion.java`

| **What** | Entity with `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private FlowGraph graph`. Spring Data/Hibernate maps the JSONB column to `FlowGraph` via Jackson. |
| **Why** | No separate `flow_node`/`flow_edge` tables — the entire graph is an immutable JSONB blob. This makes loading a version a single JOIN-free read, and there's no impedance mismatch between the React Flow export format and the stored form. |

### 4.3 `flow/service/FlowService.java`

| **What** | `createAndPublish(tenantId, name, graph)` — creates `flow` + `flow_version` in one TX, sets `flow.published_version_id`. `getPublishedVersion(flowId, tenantId)` — resolves the pinned version for a conversation. |
| **Why — ADR-006 version pinning** | In-flight conversations are pinned to the `flow_version_id` they started on. Flow authors can publish a new version without disrupting active sessions. |

---

## 5. Engine Module — Interpreter + State Machine

### 5.1 `engine/spi/NodeResult.java` (sealed interface)

| **What** | `Advance()`, `Branch(edgeKey)`, `AwaitInput()`, `AwaitExternal()`, `End()`. |
| **Why** | Sealed interface + exhaustive `instanceof` pattern matching in the engine loop: adding a new result type causes a compile error if the engine doesn't handle it. Executors cannot invent new result types without changing this interface. |

### 5.2 `engine/spi/NodeContext.java`

| **What** | Context object passed to each executor per node-step. Exposes: `nodeId()`, `node()`, `config()`, `configString(key)`, `inboundMessage()`, `isAwaitingInput()` (snapshot of pre-run status), `getVariable(name)`, `setVariable(name, value)` (creates new HashMap copy — Hibernate dirty detection), `emitMessage(OutboundMessage)`. |
| **Why — wasAwaitingInput snapshot** | The engine resets `conversation.status` to `active` before running the executor loop. Executors (e.g. `CollectInputNodeExecutor`) need the *pre-run* awaiting state to decide whether they are on the first or second pass. `wasAwaitingInput` captures this snapshot at `NodeContext` construction time. |

### 5.3 Node Executors

| Class | `supportedType()` | Behaviour |
|---|---|---|
| `TriggerNodeExecutor` | `trigger` | Always returns `Advance`. Entry point for new conversations. |
| `SendMessageNodeExecutor` | `send_message` | Interpolates `{{variableName}}` in `config.text`, parses optional `config.buttons` list, emits `OutboundMessage` to the outbox, returns `Advance`. |
| `CollectInputNodeExecutor` | `collect_input` | **Two-phase**: if `wasAwaitingInput` is false → return `AwaitInput` (engine sets `status=awaiting_input`). If `wasAwaitingInput` is true and inbound message is present → store `config.variableName` from message text → return `Advance`. |
| `ConditionNodeExecutor` | `condition` | Evaluates predicate `{variable, operator, value}` over `conversation.variables`. Operators: `not_empty`, `is_empty`, `eq`, `ne`, `contains`, `starts_with`. Returns `Branch(trueEdge)` or `Branch(falseEdge)`. |

**Why — SPI + auto-discovery:** Spring injects `List<NodeExecutor>` into `FlowEngine`. The engine builds a `Map<String, NodeExecutor>` by `supportedType()`. New node types are added by implementing `NodeExecutor` + `@Component` — the interpreter loop never changes (TRD §17).

### 5.4 `engine/domain/Conversation.java`

| **What** | JPA entity for the `conversation` table. Key fields: `currentNodeId` (engine cursor), `status` (state machine), `variables` (JSONB `Map<String,Object>`), `version` (`@Version` for optimistic locking), `flowVersionId` (pins the conversation to the version it started on). |

### 5.5 `engine/service/ConversationStateService.java`

| **What** | Read path: `load(conversationId)` — Redis hot key `conv:state:{id}` → JSON parse; on miss, load from PG and warm the cache. Write path: `save(conversation)` — PG first (authoritative), then write JSON snapshot to Redis (TTL 1 hour). `evict(conversationId)` clears the Redis key. |
| **Why — ADR-004** | PG is authoritative; Redis is a performance cache. A restart (or Redis failure) degrades to PG reads — the conversation continues correctly. Cache-write failures are non-fatal (logged, not thrown). |

### 5.6 `engine/service/FlowEngine.java`

| **What** | Main interpreter (TRD §5.4). `processInbound(channelConnectionId, tenantId, inboundMessage)`: 1) resolve contact (ContactService); 2) find/create conversation (short TX via `TransactionTemplate`); 3) acquire Redis lock (`ConversationLock.executeWithLock`); 4) run engine step (inner TX via `TransactionTemplate`): load state → execute nodes → persist → emit outbound messages. |
| **Why — TransactionTemplate** | `FlowEngine` is a `@Service` bean; `@Transactional` on private/protected methods in the same class doesn't trigger Spring AOP proxying (self-call issue). `TransactionTemplate.execute()` is the programmatic alternative that works correctly within the same bean. |
| **Why — lock outside TX** | The Redis lock must be held for the full duration of the DB TX (and the outbound send). Acquiring the lock inside the TX would allow another thread to start a second TX on the same conversation between lock acquisition and the TX starting. |
| **State machine transitions** | `active` → `awaiting_input` (AwaitInput result); `awaiting_input` → `active` (reply received, reset before node loop); `active` → `ended` (End result or no outgoing edge). |

---

## 6. Seed Flow Runner

### 6.1 `seed/SeedFlowRunner.java`

| **What** | `@Profile("local") CommandLineRunner` that seeds a demo flow on first startup. Idempotent (checks if flow name exists). Flow: `trigger → send_message("Hello! What's your name?") → collect_input(name) → condition(name not_empty) → send_message("Nice to meet you, {{name}}!") → [else] send_message("No worries!") → End`. |
| **Why** | Provides an immediate end-to-end demo in local dev without manually calling the management API. Never runs in `staging` or `prod` (profile guard). |

---

## 7. Tests

### 7.1 Unit Tests (no Docker)

| Test class | What it verifies |
|---|---|
| `AesGcmEncryptorTest` | Round-trip encrypt/decrypt; distinct IV per call; tamper detection; wrong key fails. |
| `TriggerNodeExecutorTest` | Always returns `Advance`. |
| `SendMessageNodeExecutorTest` | Variable interpolation; button parsing; missing variable → empty string; no recipient → skip outbound. |
| `CollectInputNodeExecutorTest` | First pass → `AwaitInput`; second pass with inbound → stores variable → `Advance`; second pass without inbound → `AwaitInput`. |
| `ConditionNodeExecutorTest` | All 6 operators; true/false branch; unknown operator → false. |
| `TelegramChannelAdapterTest` | `verify()` correct/wrong/missing secret; `parseInbound()` message and callback_query; `send()` calls apiClient. |
| `ConversationLockTest` | Acquire+release; returns action result; retries on contention; exhausted retries → exception; key contains conversationId. |
| `FlowEngineTest` | Linear flow → `AwaitInput` on first message; second message → `ended`; outbound message has correct recipient. |

### 7.2 Integration Tests (`@Tag("integration")`, require Docker)

| Test class | What it verifies |
|---|---|
| `V2MigrationTest` | All Phase 1 tables exist; `version` column present; RLS enabled on all 8 tables; JSONB `graph` column; unique index on `contact_identity`. |
| `Phase1RlsIsolationTest` | Contact rows isolated per tenant; cross-tenant write attempt (with GUC override) behaviour verified. |
| `EngineIntegrationTest` | Webhook → engine → outbound via mocked `TelegramApiClient`; `conversation.status = awaiting_input` after first message; second message → `status = ended`; invalid webhook secret → 200 but no engine. |
| `SurvivesRestartTest` | First message → `awaiting_input`; Redis key evicted (restart simulation); second message → engine rehydrates from PG → `status = ended`. |

### 7.3 `build.gradle.kts` fix

The `integrationTest` task was missing `testClassesDirs` and `classpath` — it had no source. Fixed by adding:
```kotlin
testClassesDirs = sourceSets["test"].output.classesDirs
classpath = sourceSets["test"].runtimeClasspath
```

---

## 8. Configuration Changes

| File | Change |
|---|---|
| `application.yml` | Added `app.telegram.base-url: ${TELEGRAM_BASE_URL:https://api.telegram.org}` |
| `application-test.yml` | Added `app.security.encryption.master-key: "000...0"` (64 hex zeros); `app.telegram.base-url: http://localhost:${test.telegram.mock.port:9999}`; removed Redis health disable (real Redis Testcontainer now used) |
| `application-local.yml` | Added Telegram config placeholder comment |
| `FlowpilotApplication.java` | Added `EncryptionProperties.class` to `@EnableConfigurationProperties` |
| `SecurityConfig.java` | Added `"/webhooks/telegram/*"` to `PUBLIC_POST` array |
| `AbstractIntegrationTest.java` | Added Redis Testcontainer (`redis:7-alpine`) + `DynamicPropertySource` for Redis host/port |
| `infra/.env.example` | Added `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_BASE_URL`, `TELEGRAM_BASE_URL` placeholders |

---

## 9. Deferred / Out of Scope

| Item | Deferred to |
|---|---|
| `delay` node + scheduler poll for `waiting` conversations | Phase 2 |
| `human_handoff` node | Phase 3 |
| `ai_intent` and `action_*` nodes | Phase 2 |
| Benchmark smoke test | Dedicated checkpoint after Phase 1 |
| Flow builder REST API | Phase 4 |
| Web widget channel adapter | Phase 2 |
| `waiting` and `handoff` state transitions | Phase 2/3 (columns present, executor not wired) |

---

## 10. Exit Criteria Status

| Criterion | Status |
|---|---|
| Engine executes all 4 Phase 1 node types (trigger, send_message, collect_input, condition) | ✅ |
| Conversation state persists to PG (authoritative) + Redis (hot copy) | ✅ |
| State survives restart (Redis flush → PG rehydration) | ✅ `SurvivesRestartTest` |
| Per-conversation Redis lock serializes concurrent writes | ✅ `ConversationLockTest` |
| Bot token never stored in plaintext | ✅ AES-GCM ciphertext only |
| RLS isolation holds for all Phase 1 tables | ✅ `Phase1RlsIsolationTest` |
| Unit tests pass without Docker | ✅ 57 tests green |
| Integration tests (schema + engine + restart) compile and are ready for CI with Docker | ✅ |
| No secrets committed | ✅ |
