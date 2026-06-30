package com.iacross.flowpilot.channel.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Telegram Update object — only fields needed by Phase 1 are mapped.
 * Inbound via POST /webhooks/telegram/{botRef}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(
    @JsonProperty("update_id")    long updateId,
    @JsonProperty("message")      TelegramMessage message,
    @JsonProperty("callback_query") TelegramCallbackQuery callbackQuery
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramMessage(
        @JsonProperty("message_id") long messageId,
        @JsonProperty("chat")       TelegramChat chat,
        @JsonProperty("from")       TelegramUser from,
        @JsonProperty("text")       String text
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramCallbackQuery(
        @JsonProperty("id")      String id,
        @JsonProperty("from")    TelegramUser from,
        @JsonProperty("message") TelegramMessage message,
        @JsonProperty("data")    String data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramChat(
        @JsonProperty("id")         long id,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name")  String lastName,
        @JsonProperty("username")   String username
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramUser(
        @JsonProperty("id")         long id,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name")  String lastName,
        @JsonProperty("username")   String username
    ) {}
}
