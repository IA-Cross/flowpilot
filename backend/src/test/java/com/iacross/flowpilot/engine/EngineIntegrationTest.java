package com.iacross.flowpilot.engine;

import com.iacross.flowpilot.AbstractIntegrationTest;
import com.iacross.flowpilot.channel.telegram.TelegramApiClient;
import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.engine.repository.ConversationRepository;
import com.iacross.flowpilot.flow.domain.*;
import com.iacross.flowpilot.flow.service.FlowService;
import com.iacross.flowpilot.shared.security.AesGcmEncryptor;
import com.iacross.flowpilot.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * End-to-end engine integration test.
 *
 * Sets up a real tenant + flow + channel_connection in PG, then exercises the Telegram
 * webhook endpoint. TelegramApiClient is mocked to capture outbound sendMessage calls.
 *
 * Verifies:
 *  - Inbound webhook → engine steps trigger→send_message→collect_input (stops at AwaitInput)
 *  - Outbound message is sent via the adapter
 *  - conversation.status advances to awaiting_input
 *  - Second webhook (user reply) → collect_input stores variable → conversation ends
 */
@DisplayName("FlowEngine end-to-end integration")
class EngineIntegrationTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired FlowService flowService;
    @Autowired ConversationRepository conversationRepo;
    @Autowired AesGcmEncryptor encryptor;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate txTemplate;

    @MockBean
    TelegramApiClient telegramApiClient;

    private UUID tenantId;
    private UUID channelConnectionId;

    @BeforeEach
    void setUp() {
        Mockito.reset(telegramApiClient);

        // 1. Create tenant + user
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant(id,name,slug,status) VALUES(?,?,?,?)",
            tenantId, "engine-test", "engine-test-" + tenantId.toString().substring(0,8), "active");

        TenantContext.set(tenantId);
        try {
            txTemplate.execute(st -> {
                jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");

                // 2. Create and publish the demo flow
                FlowVersion version = flowService.createAndPublish(tenantId, "Engine Test Flow",
                    buildDemoGraph());

                // 3. Create channel_connection with encrypted bot token
                channelConnectionId = UUID.randomUUID();
                byte[] encBotToken = encryptor.encrypt("fake-bot-token");
                byte[] encWebhookSecret = encryptor.encrypt("webhook-secret");
                jdbc.update(
                    "INSERT INTO channel_connection "
                    + "(id,tenant_id,type,name,status,secret_ciphertext,webhook_secret_ciphertext,flow_id) "
                    + "VALUES(?,?,?,?,?,?,?,?)",
                    channelConnectionId, tenantId, "telegram", "Test Bot",
                    "connected", encBotToken, encWebhookSecret,
                    jdbc.queryForObject("SELECT flow_id FROM flow_version WHERE id = ?",
                        UUID.class, version.getId()));
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
    @DisplayName("Webhook triggers engine: greeting sent, conversation awaits input")
    void webhookTriggersEngine_conversationAwaitsInput() {
        String updateBody = telegramUpdate(111L, "start", "msg-1");
        ResponseEntity<Void> response = postWebhook(channelConnectionId, updateBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Adapter must have sent the greeting
        verify(telegramApiClient, atLeastOnce()).sendMessage(eq("fake-bot-token"), eq(111L), any());

        // Conversation should be in awaiting_input state
        List<Conversation> convs = jdbc.query(
            "SELECT * FROM conversation WHERE channel_connection_id = ?",
            (rs, i) -> {
                var c = new Conversation();
                c.setId((UUID) rs.getObject("id"));
                c.setStatus(rs.getString("status"));
                return c;
            }, channelConnectionId);
        assertThat(convs).hasSize(1);
        assertThat(convs.get(0).getStatus()).isEqualTo("awaiting_input");
    }

    @Test
    @DisplayName("Second webhook (reply) completes collect_input and ends conversation")
    void secondWebhook_completesFlow() {
        // First message — starts conversation
        postWebhook(channelConnectionId, telegramUpdate(222L, "start", "msg-1"));

        // Reset mock counts so we only check the second call
        Mockito.reset(telegramApiClient);

        // Second message — user provides name
        postWebhook(channelConnectionId, telegramUpdate(222L, "Alice", "msg-2"));

        // Conversation should now be ended
        String status = jdbc.queryForObject(
            "SELECT status FROM conversation WHERE channel_connection_id = ? ORDER BY started_at DESC LIMIT 1",
            String.class, channelConnectionId);
        assertThat(status).isEqualTo("ended");
    }

    @Test
    @DisplayName("Invalid webhook secret returns 200 but does not start engine")
    void invalidWebhookSecret_isIgnored() {
        String update = telegramUpdate(333L, "hack", "msg-x");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Telegram-Bot-Api-Secret-Token", "wrong-secret");
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(update, headers);

        ResponseEntity<Void> response = restTemplate.postForEntity(
            "/webhooks/telegram/" + channelConnectionId, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(telegramApiClient, never()).sendMessage(any(), anyLong(), any());
    }

    // -----------------------------------------------------------------------

    private ResponseEntity<Void> postWebhook(UUID channelId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Telegram-Bot-Api-Secret-Token", "webhook-secret");
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
                "message_id": %s,
                "chat": {"id": %d, "first_name": "TestUser"},
                "from": {"id": %d, "first_name": "TestUser", "is_bot": false},
                "text": "%s"
              }
            }""".formatted(System.currentTimeMillis(), msgId, chatId, chatId, text);
    }

    private FlowGraph buildDemoGraph() {
        var trigger  = new FlowNode("n1", "trigger",      Map.of(), Map.of());
        var greet    = new FlowNode("n2", "send_message",  Map.of("text", "Hello! What is your name?"), Map.of());
        var collect  = new FlowNode("n3", "collect_input", Map.of("variableName", "name"), Map.of());
        return new FlowGraph(
            List.of(trigger, greet, collect),
            List.of(
                new FlowEdge("e1", "n1", "n2", null),
                new FlowEdge("e2", "n2", "n3", null)
            )
        );
    }
}
