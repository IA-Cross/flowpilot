package com.iacross.flowpilot.engine.domain;

import com.iacross.flowpilot.shared.id.UuidV7;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only execution log for one conversation (node entries, branches, awaits, errors). */
@Entity
@Table(name = "conversation_event")
@EntityListeners(AuditingEntityListener.class)
public class ConversationEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "node_id")
    private String nodeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb")
    private Map<String, Object> data;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void assignId() {
        if (this.id == null) this.id = UuidV7.generate();
    }

    public static ConversationEvent of(UUID tenantId, UUID conversationId, String type, String nodeId) {
        var e = new ConversationEvent();
        e.tenantId = tenantId;
        e.conversationId = conversationId;
        e.type = type;
        e.nodeId = nodeId;
        return e;
    }

    // Getters / Setters

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public Instant getCreatedAt() { return createdAt; }
}
