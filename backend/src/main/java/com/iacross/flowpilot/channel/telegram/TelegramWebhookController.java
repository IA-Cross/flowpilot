package com.iacross.flowpilot.channel.telegram;

import com.iacross.flowpilot.channel.domain.ChannelConnection;
import com.iacross.flowpilot.channel.repository.ChannelConnectionRepository;
import com.iacross.flowpilot.channel.spi.InboundMessage;
import com.iacross.flowpilot.channel.spi.InboundRequest;
import com.iacross.flowpilot.engine.service.FlowEngine;
import com.iacross.flowpilot.shared.security.AesGcmEncryptor;
import com.iacross.flowpilot.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Telegram webhook receiver (TRD §5.3, §8.1).
 *
 * POST /webhooks/telegram/{channelConnectionId}
 *   — Publicly accessible (no JWT); Telegram sends updates here after setWebhook.
 *   — {channelConnectionId} is used to look up channel_connection BEFORE setting
 *     the tenant context (privileged lookup, outside RLS).
 *   — Verifies X-Telegram-Bot-Api-Secret-Token against the stored (decrypted) secret.
 *   — Thin: verify → set tenant context → dispatch to FlowEngine → return 200 immediately.
 *
 * Whitelisted in SecurityConfig.PUBLIC_POST.
 */
@RestController
@RequestMapping("/webhooks/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final ChannelConnectionRepository channelRepo;
    private final TelegramChannelAdapter adapter;
    private final AesGcmEncryptor encryptor;
    private final FlowEngine engine;

    public TelegramWebhookController(ChannelConnectionRepository channelRepo,
                                     TelegramChannelAdapter adapter,
                                     AesGcmEncryptor encryptor,
                                     FlowEngine engine) {
        this.channelRepo = channelRepo;
        this.adapter = adapter;
        this.encryptor = encryptor;
        this.engine = engine;
    }

    @PostMapping("/{channelConnectionId}")
    public ResponseEntity<Void> receive(
            @PathVariable UUID channelConnectionId,
            HttpServletRequest httpRequest) throws IOException {

        // 1. Privileged pre-RLS lookup — resolve tenant from the channel connection
        ChannelConnection channel = channelRepo.findByIdForWebhook(channelConnectionId)
            .orElse(null);
        if (channel == null) {
            log.warn("Webhook received for unknown channel: {}", channelConnectionId);
            return ResponseEntity.ok().build(); // 200 always (Telegram retries on errors)
        }

        // 2. Read raw body
        String rawBody = new String(httpRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        InboundRequest inboundRequest = InboundRequest.from(httpRequest, rawBody);

        // 3. Verify webhook secret token
        String webhookSecret = encryptor.decryptToString(channel.getWebhookSecretCiphertext());
        if (!adapter.verify(inboundRequest, webhookSecret)) {
            log.warn("Webhook secret verification failed for channel: {}", channelConnectionId);
            return ResponseEntity.ok().build(); // 200 to avoid Telegram retries; just ignore
        }

        // 4. Set tenant context
        TenantContext.set(channel.getTenantId());
        try {
            // 5. Parse and dispatch — engine handles bot token decryption internally
            InboundMessage inbound = adapter.parseInbound(inboundRequest);
            engine.processInbound(channelConnectionId, channel.getTenantId(), inbound);
        } catch (Exception e) {
            log.error("Engine error processing Telegram update for channel {}: {}", channelConnectionId, e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }

        return ResponseEntity.ok().build();
    }
}
