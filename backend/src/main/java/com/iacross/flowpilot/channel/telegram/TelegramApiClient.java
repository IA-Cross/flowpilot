package com.iacross.flowpilot.channel.telegram;

import com.iacross.flowpilot.channel.spi.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocking HTTP client for the Telegram Bot API.
 * Uses Spring RestClient (blocking, Loom-friendly — not WebClient).
 * Callers pass the bot token per request — it is never stored in this component.
 */
@Component
public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);

    private final String baseUrl;
    private final RestClient restClient;

    public TelegramApiClient(@Value("${app.telegram.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Register this app as the webhook receiver for the given bot token.
     * Called once when a channel_connection is first created.
     */
    public void setWebhook(String botToken, String webhookUrl, String secretToken) {
        Map<String, Object> body = new HashMap<>();
        body.put("url", webhookUrl);
        body.put("secret_token", secretToken);
        body.put("allowed_updates", List.of("message", "callback_query"));

        var response = restClient.post()
            .uri("/bot{token}/setWebhook", botToken)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(Map.class);

        log.info("setWebhook response: {}", response.getBody());
    }

    /** Send a plain-text or inline-keyboard message to a Telegram chat. */
    public void sendMessage(String botToken, long chatId, OutboundMessage outbound) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", outbound.text());

        if (outbound.hasButtons()) {
            body.put("reply_markup", buildInlineKeyboard(outbound.buttons()));
        }

        restClient.post()
            .uri("/bot{token}/sendMessage", botToken)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    private Map<String, Object> buildInlineKeyboard(List<OutboundMessage.Button> buttons) {
        // Each button is its own row (single-column layout)
        List<List<Map<String, String>>> keyboard = new ArrayList<>();
        for (var btn : buttons) {
            keyboard.add(List.of(Map.of("text", btn.label(), "callback_data", btn.value())));
        }
        return Map.of("inline_keyboard", keyboard);
    }
}
