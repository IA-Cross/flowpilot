package com.iacross.flowpilot.channel.management;

import com.iacross.flowpilot.channel.domain.ChannelConnection;
import com.iacross.flowpilot.channel.repository.ChannelConnectionRepository;
import com.iacross.flowpilot.channel.telegram.TelegramApiClient;
import com.iacross.flowpilot.shared.security.AesGcmEncryptor;
import com.iacross.flowpilot.shared.tenant.RlsTenantInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ChannelManagementService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChannelConnectionRepository repo;
    private final AesGcmEncryptor encryptor;
    private final TelegramApiClient telegram;
    private final JdbcTemplate jdbc;
    private final String appBaseUrl;

    public ChannelManagementService(ChannelConnectionRepository repo,
                                    AesGcmEncryptor encryptor,
                                    TelegramApiClient telegram,
                                    JdbcTemplate jdbc,
                                    @Value("${server.base-url:http://localhost:8080}") String appBaseUrl) {
        this.repo = repo;
        this.encryptor = encryptor;
        this.telegram = telegram;
        this.jdbc = jdbc;
        this.appBaseUrl = appBaseUrl;
    }

    public record ConnectTelegramCommand(String botToken, String name, UUID flowId) {}

    /**
     * Connect a Telegram bot: encrypt credentials, register webhook, persist.
     * botToken is NEVER stored in plaintext — only as AES-GCM ciphertext.
     */
    public ChannelConnection connectTelegram(ConnectTelegramCommand cmd, UUID tenantId) {
        RlsTenantInterceptor.applyToCurrentTransaction(jdbc);

        // Generate a random webhook secret (32 bytes → base64url)
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String webhookSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        var channel = new ChannelConnection();
        channel.setTenantId(tenantId);
        channel.setType("telegram");
        channel.setName(cmd.name());
        channel.setStatus("disconnected");
        channel.setSecretCiphertext(encryptor.encrypt(cmd.botToken()));
        channel.setWebhookSecretCiphertext(encryptor.encrypt(webhookSecret));
        channel.setFlowId(cmd.flowId());
        repo.save(channel);

        // Register webhook with Telegram; update status to connected
        String webhookUrl = appBaseUrl + "/webhooks/telegram/" + channel.getId();
        telegram.setWebhook(cmd.botToken(), webhookUrl, webhookSecret);

        channel.setStatus("connected");
        return repo.save(channel);
    }

    public List<ChannelConnection> listForTenant(UUID tenantId) {
        RlsTenantInterceptor.applyToCurrentTransaction(jdbc);
        return repo.findAll().stream()
            .filter(c -> c.getTenantId().equals(tenantId))
            .toList();
    }
}
