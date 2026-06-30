package com.iacross.flowpilot.engine;

import com.iacross.flowpilot.AbstractIntegrationTest;
import com.iacross.flowpilot.channel.telegram.TelegramApiClient;
import com.iacross.flowpilot.engine.repository.ConversationRepository;
import com.iacross.flowpilot.engine.service.ConversationStateService;
import com.iacross.flowpilot.flow.domain.*;
import com.iacross.flowpilot.flow.service.FlowService;
import com.iacross.flowpilot.shared.security.AesGcmEncryptor;
import com.iacross.flowpilot.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies ADR-004: conversation state survives a backend restart (Redis flush).
 *
 * Scenario:
 *  1. Send a message → conversation reaches awaiting_input and is cached in Redis.
 *  2. Flush the Redis key (simulate restart / Redis loss).
 *  3. Send a reply → engine must rehydrate from PG and complete the flow correctly.
 */
@DisplayName("Conversation state survives restart (ADR-004)")
class SurvivesRestartTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired FlowService flowService;
    @Autowired ConversationRepository conversationRepo;
    @Autowired ConversationStateService stateService;
    @Autowired AesGcmEncryptor encryptor;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate txTemplate;
    @Autowired StringRedisTemplate redis;

    @MockBean
    TelegramApiClient telegramApiClient;

    private UUID tenantId;
    private UUID channelConnectionId;

    @BeforeEach
    void setUp() {
        Mockito.reset(telegramApiClient);

        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant(id,name,slug,status) VALUES(?,?,?,?)",
            tenantId, "restart-test", "restart-test-" + tenantId.toString().substring(0,8), "active");

        TenantContext.set(tenantId);
        try {
            txTemplate.execute(st -> {
                jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");

                FlowVersion version = flowService.createAndPublish(tenantId, "Restart Test Flow",
                    buildDemoGraph());

                channelConnectionId = UUID.randomUUID();
                byte[] encBotToken = encryptor.encrypt("fake-bot-token-restart");
                byte[] encWebhookSecret = encryptor.encrypt("restart-secret");
                UUID flowId = jdbc.queryForObject(
                    "SELECT flow_id FROM flow_version WHERE id = ?", UUID.class, version.getId());
                jdbc.update(
                    "INSERT INTO channel_connection "
                    + "(id,tenant_id,type,name,status,secret_ciphertext,webhook_secret_ciphertext,flow_id) "
                    + "VALUES(?,?,?,?,?,?,?,?)",
                    channelConnectionId, tenantId, "telegram", "Restart Bot",
                    "connected", encBotToken, encWebhookSecret, flowId);
                return null;
            });
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Engine rehydrates from PG when Redis cache is empty after restart")
    void rehydratesFromPgAfterRedisFlushed() {
        // Step 1: First message → conversation reaches awaiting_input
        postWebhook(channelConnectionId, telegramUpdate(999L, "hello", "m1"), "restart-secret");

        // Confirm conversation is awaiting_input
        String status1 = jdbc.queryForObject(
            "SELECT status FROM conversation WHERE channel_connection_id = ? ORDER BY started_at DESC LIMIT 1",
            String.class, channelConnectionId);
        assertThat(status1).isEqualTo("awaiting_input");

        // Step 2: Simulate Redis loss — evict ALL conv:state:* keys
        UUID conversationId = jdbc.queryForObject(
            "SELECT id FROM conversation WHERE channel_connection_id = ?", UUID.class, channelConnectionId);
        stateService.evict(conversationId); // clears Redis key
        // Verify cache is gone
        assertThat(redis.hasKey("conv:state:" + conversationId)).isFalse();

        // Step 3: Second message (user reply) — engine must rehydrate from PG
        Mockito.reset(telegramApiClient);
        postWebhook(channelConnectionId, telegramUpdate(999L, "Alice", "m2"), "restart-secret");

        // Conversation should now be ended (PG-rehydrated engine completed the flow)
        String status2 = jdbc.queryForObject(
            "SELECT status FROM conversation WHERE channel_connection_id = ? ORDER BY started_at DESC LIMIT 1",
            String.class, channelConnectionId);
        assertThat(status2).isEqualTo("ended");
    }

    // -----------------------------------------------------------------------

    private ResponseEntity<Void> postWebhook(UUID channelId, String body, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Telegram-Bot-Api-Secret-Token", secret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
            "/webhooks/telegram/" + channelId,
            new HttpEntity<>(body, headers),
            Void.class);
    }

    private String telegramUpdate(long chatId, String text, String msgId) {
        return """
            {
              "update_id": %d,
              "message": {
                "message_id": "%s",
                "chat": {"id": %d, "first_name": "RestartUser"},
                "from": {"id": %d, "first_name": "RestartUser", "is_bot": false},
                "text": "%s"
              }
            }""".formatted(System.currentTimeMillis(), msgId, chatId, chatId, text);
    }

    private FlowGraph buildDemoGraph() {
        var trigger = new FlowNode("n1", "trigger",      Map.of(), Map.of());
        var greet   = new FlowNode("n2", "send_message",  Map.of("text", "Hello!"), Map.of());
        var collect = new FlowNode("n3", "collect_input", Map.of("variableName", "name"), Map.of());
        return new FlowGraph(
            List.of(trigger, greet, collect),
            List.of(
                new FlowEdge("e1", "n1", "n2", null),
                new FlowEdge("e2", "n2", "n3", null)
            )
        );
    }
}
