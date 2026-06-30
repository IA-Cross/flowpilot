package com.iacross.flowpilot.channel.contact;

import com.iacross.flowpilot.shared.id.UuidV7;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps a channel-specific external identifier to a canonical Contact.
 * UNIQUE (tenant_id, channel, external_id) enforced in DB and relied on
 * by ContactService for upsert / dedup.
 */
@Entity
@Table(name = "contact_identity")
@EntityListeners(AuditingEntityListener.class)
public class ContactIdentity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    /** Stored as lowercase db value: 'telegram' or 'web_widget'. */
    @Column(name = "channel", nullable = false)
    private String channel;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void assignId() {
        if (this.id == null) this.id = UuidV7.generate();
    }

    // ---- Getters / Setters ----

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public Instant getCreatedAt() { return createdAt; }
}
