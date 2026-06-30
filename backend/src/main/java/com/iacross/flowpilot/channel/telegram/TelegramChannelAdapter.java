package com.iacross.flowpilot.channel.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iacross.flowpilot.channel.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Telegram ChannelAdapter (ADR-005, TRD §5.3, §8.1-8.2).
 *
 * verify()  — validates X-Telegram-Bot-Api-Secret-Token header.
 * parseInbound() — maps Telegram Update (message + callback_query) → InboundMessage.
 * send()    — renders text + inline keyboard via TelegramApiClient.
 *
 * The bot token is passed per-call (never stored here).
 */
@Component
public class TelegramChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelAdapter.class);
    private static final String SECRET_HEADER = "x-telegram-bot-api-secret-token";

    private final ObjectMapper objectMapper;
    private final TelegramApiClient apiClient;

    public TelegramChannelAdapter(ObjectMapper objectMapper, TelegramApiClient apiClient) {
        this.objectMapper = objectMapper;
        this.apiClient = apiClient;
    }

    @Override
    public ChannelType type() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public boolean verify(InboundRequest request, String expectedSecret) {
        String received = request.header(SECRET_HEADER);
        if (received == null || expectedSecret == null) return false;
        // Constant-time comparison to prevent timing attacks
        return received.equals(expectedSecret);
    }

    @Override
    public InboundMessage parseInbound(InboundRequest request) {
        TelegramUpdate update;
        try {
            update = objectMapper.readValue(request.rawBody(), TelegramUpdate.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Telegram update", e);
        }

        if (update.message() != null && update.message().text() != null) {
            var msg = update.message();
            var identity = new ChannelIdentity(ChannelType.TELEGRAM, String.valueOf(msg.chat().id()));
            return new InboundMessage(identity, msg.text(), String.valueOf(msg.messageId()));
        }

        if (update.callbackQuery() != null) {
            var cq = update.callbackQuery();
            var chatId = cq.message() != null ? cq.message().chat().id() : cq.from().id();
            var identity = new ChannelIdentity(ChannelType.TELEGRAM, String.valueOf(chatId));
            return new InboundMessage(identity, cq.data(), cq.id());
        }

        throw new IllegalArgumentException("Unsupported Telegram update type: " + update.updateId());
    }

    @Override
    public ChannelIdentity identityOf(InboundMessage message) {
        return message.identity();
    }

    @Override
    public void send(OutboundMessage message, String botToken) {
        long chatId = Long.parseLong(message.recipient().externalId());
        apiClient.sendMessage(botToken, chatId, message);
    }
}
