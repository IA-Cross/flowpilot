package com.iacross.flowpilot.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iacross.flowpilot.channel.spi.*;
import com.iacross.flowpilot.channel.telegram.TelegramApiClient;
import com.iacross.flowpilot.channel.telegram.TelegramChannelAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TelegramChannelAdapterTest {

    private TelegramChannelAdapter adapter;
    private TelegramApiClient apiClient;

    @BeforeEach
    void setUp() {
        apiClient = Mockito.mock(TelegramApiClient.class);
        adapter = new TelegramChannelAdapter(new ObjectMapper(), apiClient);
    }

    // ------ verify() ------

    @Test
    void verify_correctSecret_returnsTrue() {
        var req = new InboundRequest(
            Map.of("x-telegram-bot-api-secret-token", "my-secret"), "{}");
        assertThat(adapter.verify(req, "my-secret")).isTrue();
    }

    @Test
    void verify_wrongSecret_returnsFalse() {
        var req = new InboundRequest(
            Map.of("x-telegram-bot-api-secret-token", "wrong"), "{}");
        assertThat(adapter.verify(req, "my-secret")).isFalse();
    }

    @Test
    void verify_missingHeader_returnsFalse() {
        var req = new InboundRequest(Map.of(), "{}");
        assertThat(adapter.verify(req, "my-secret")).isFalse();
    }

    @Test
    void verify_nullExpected_returnsFalse() {
        var req = new InboundRequest(
            Map.of("x-telegram-bot-api-secret-token", "something"), "{}");
        assertThat(adapter.verify(req, null)).isFalse();
    }

    // ------ parseInbound() — message ------

    @Test
    void parseInbound_textMessage_mapsCorrectly() {
        String body = """
            {
              "update_id": 1,
              "message": {
                "message_id": 42,
                "chat": {"id": 123456, "first_name": "Alice"},
                "from": {"id": 123456, "first_name": "Alice", "is_bot": false},
                "text": "Hello!"
              }
            }""";
        InboundMessage msg = adapter.parseInbound(new InboundRequest(Map.of(), body));
        assertThat(msg.text()).isEqualTo("Hello!");
        assertThat(msg.identity().externalId()).isEqualTo("123456");
        assertThat(msg.identity().channel()).isEqualTo(ChannelType.TELEGRAM);
        assertThat(msg.channelMessageId()).isEqualTo("42");
    }

    @Test
    void parseInbound_callbackQuery_mapsData() {
        String body = """
            {
              "update_id": 2,
              "callback_query": {
                "id": "cq-id",
                "from": {"id": 777, "first_name": "Bob", "is_bot": false},
                "message": {
                  "message_id": 99,
                  "chat": {"id": 777, "first_name": "Bob"},
                  "text": "Choose:"
                },
                "data": "yes"
              }
            }""";
        InboundMessage msg = adapter.parseInbound(new InboundRequest(Map.of(), body));
        assertThat(msg.text()).isEqualTo("yes");
        assertThat(msg.identity().externalId()).isEqualTo("777");
    }

    @Test
    void parseInbound_invalidJson_throws() {
        var req = new InboundRequest(Map.of(), "not-json");
        assertThatThrownBy(() -> adapter.parseInbound(req))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ------ send() ------

    @Test
    void send_callsApiClientWithCorrectChatId() {
        var outbound = new OutboundMessage(
            new ChannelIdentity(ChannelType.TELEGRAM, "555"), "Hi!", null, "n1");
        adapter.send(outbound, "bot-token");
        Mockito.verify(apiClient).sendMessage("bot-token", 555L, outbound);
    }
}
