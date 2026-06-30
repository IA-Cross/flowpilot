package com.iacross.flowpilot.identity.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh-token record implementing rotation.
 * On each use a new token is issued and this row is updated with {@code replacedBy}.
 * A token is considered valid only when: not revoked, not expired, {@code replacedBy} is null.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** BCrypt or SHA-256 hash of the raw token value (raw is never persisted). */
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** ID of the token that replaced this one during rotation. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UuidV7.generate();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    // ---- Helpers ----

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isRevoked() { return revokedAt != null; }
    public boolean isActive()  { return !isExpired() && !isRevoked() && replacedBy == null; }

    // ---- Getters / Setters ----

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public UUID getReplacedBy() { return replacedBy; }
    public void setReplacedBy(UUID replacedBy) { this.replacedBy = replacedBy; }

    public Instant getCreatedAt() { return createdAt; }
}
