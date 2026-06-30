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

@Entity
@Table(name = "message")
@EntityListeners(AuditingEntityListener.class)
public class Message {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** direction: inbound | outbound */
    @Column(name = "direction", nullable = false)
    private String direction;

    /** content_type: text | media | buttons | system */
    @Column(name = "content_type", nullable = false)
    private String contentType = "text";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> body;

    @Column(name = "produced_by_node_id")
    private String producedByNodeId;

    @Column(name = "channel_message_id")
    private String channelMessageId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void assignId() {
        if (this.id == null) this.id = UuidV7.generate();
    }

    // Getters / Setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Map<String, Object> getBody() { return body; }
    public void setBody(Map<String, Object> body) { this.body = body; }

    public String getProducedByNodeId() { return producedByNodeId; }
    public void setProducedByNodeId(String producedByNodeId) { this.producedByNodeId = producedByNodeId; }

    public String getChannelMessageId() { return channelMessageId; }
    public void setChannelMessageId(String channelMessageId) { this.channelMessageId = channelMessageId; }

    public Instant getCreatedAt() { return createdAt; }
}
