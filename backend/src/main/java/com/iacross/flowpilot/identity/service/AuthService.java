package com.iacross.flowpilot.identity.service;

import com.iacross.flowpilot.identity.domain.*;
import com.iacross.flowpilot.identity.repository.*;
import com.iacross.flowpilot.shared.security.JwtProperties;
import com.iacross.flowpilot.shared.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Core auth operations: register, login, refresh-token rotation, logout.
 *
 * Refresh-token strategy (TRD §5.2):
 *  - On login: issue an opaque random token, store its SHA-256 hash.
 *  - On refresh: find hash, verify active, issue new pair, mark old token
 *    as replaced_by (rotation). The old raw token is immediately unusable.
 *  - On logout: mark all user tokens revoked.
 */
@Service
@Transactional
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final TenantRepository tenantRepo;
    private final AppUserRepository userRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    public AuthService(TenantRepository tenantRepo,
                       AppUserRepository userRepo,
                       RefreshTokenRepository refreshTokenRepo,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       JwtProperties jwtProperties) {
        this.tenantRepo = tenantRepo;
        this.userRepo = userRepo;
        this.refreshTokenRepo = refreshTokenRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.jwtProperties = jwtProperties;
    }

    // ---- Register ----

    public record RegisterCommand(String tenantName, String email, String password, String displayName) {}
    public record AuthTokens(String accessToken, String rawRefreshToken) {}

    /** Create a new tenant workspace and its owner user, then issue tokens. */
    public AuthTokens register(RegisterCommand cmd) {
        if (userRepo.existsByEmail(cmd.email())) {
            throw new AuthException("Email already in use");
        }
        String slug = toSlug(cmd.tenantName());
        if (tenantRepo.existsBySlug(slug)) {
            slug = slug + "-" + UUID.randomUUID().toString().substring(0, 6);
        }

        var tenant = new Tenant();
        tenant.setName(cmd.tenantName());
        tenant.setSlug(slug);
        tenantRepo.save(tenant);

        var user = new AppUser();
        user.setTenantId(tenant.getId());
        user.setEmail(cmd.email().toLowerCase().strip());
        user.setPasswordHash(passwordEncoder.encode(cmd.password()));
        user.setDisplayName(cmd.displayName() != null ? cmd.displayName() : "");
        userRepo.save(user);

        return issueTokenPair(user, tenant.getId());
    }

    // ---- Login ----

    public record LoginCommand(String email, String password) {}

    public AuthTokens login(LoginCommand cmd) {
        AppUser user = userRepo.findByEmailNative(cmd.email().toLowerCase().strip())
                .orElseThrow(() -> new AuthException("Invalid credentials"));

        if (user.getStatus() == AppUser.UserStatus.DISABLED) {
            throw new AuthException("Account is disabled");
        }
        if (!passwordEncoder.matches(cmd.password(), user.getPasswordHash())) {
            throw new AuthException("Invalid credentials");
        }
        return issueTokenPair(user, user.getTenantId());
    }

    // ---- Refresh (rotation) ----

    public record RefreshCommand(String rawRefreshToken) {}

    public AuthTokens refresh(RefreshCommand cmd) {
        String hash = hashToken(cmd.rawRefreshToken());
        RefreshToken existing = refreshTokenRepo.findByTokenHash(hash)
                .orElseThrow(() -> new AuthException("Invalid refresh token"));

        if (!existing.isActive()) {
            // Token already used or revoked — potential token-theft; revoke the whole family
            refreshTokenRepo.revokeAllForUser(existing.getUserId());
            throw new AuthException("Refresh token is no longer valid");
        }

        AppUser user = userRepo.findById(existing.getUserId())
                .orElseThrow(() -> new AuthException("User not found"));

        // Rotate: issue new pair, mark old as replaced
        AuthTokens newTokens = issueTokenPair(user, user.getTenantId());

        // Find the newly persisted token (the one just issued) and link rotation chain
        String newHash = hashToken(newTokens.rawRefreshToken());
        refreshTokenRepo.findByTokenHash(newHash).ifPresent(newTok -> {
            existing.setReplacedBy(newTok.getId());
        });
        refreshTokenRepo.save(existing);

        return newTokens;
    }

    // ---- Logout ----

    public void logout(UUID userId) {
        refreshTokenRepo.revokeAllForUser(userId);
    }

    // ---- Helpers ----

    private AuthTokens issueTokenPair(AppUser user, UUID tenantId) {
        String accessToken = jwtUtil.issueAccessToken(user.getId(), tenantId);
        String rawRefresh  = generateRawToken();

        var rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(hashToken(rawRefresh));
        rt.setExpiresAt(Instant.now().plusMillis(jwtProperties.refreshTokenExpiryMs()));
        refreshTokenRepo.save(rt);

        return new AuthTokens(accessToken, rawRefresh);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String raw) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String toSlug(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
