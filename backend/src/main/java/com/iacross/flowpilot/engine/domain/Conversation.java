package com.iacross.flowpilot.engine.domain;

import com.iacross.flowpilot.shared.id.UuidV7;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "conversation")
@EntityListeners(AuditingEntityListener.class)
public class Conversation {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "channel_connection_id", nullable = false)
    private UUID channelConnectionId;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Column(name = "flow_version_id", nullable = false)
    private UUID flowVersionId;

    @Column(name = "current_node_id")
    private String currentNodeId;

    /** status: active | awaiting_input | waiting | handoff | ended */
    @Column(name = "status", nullable = false)
    private String status = "active";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> variables = new HashMap<>();

    @Column(name = "wait_until")
    private Instant waitUntil;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void assignId() {
        if (this.id == null) this.id = UuidV7.generate();
        if (this.startedAt == null) this.startedAt = Instant.now();
        if (this.lastActivityAt == null) this.lastActivityAt = Instant.now();
    }

    // Getters / Setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }

    public UUID getChannelConnectionId() { return channelConnectionId; }
    public void setChannelConnectionId(UUID channelConnectionId) { this.channelConnectionId = channelConnectionId; }

    public UUID getFlowId() { return flowId; }
    public void setFlowId(UUID flowId) { this.flowId = flowId; }

    public UUID getFlowVersionId() { return flowVersionId; }
    public void setFlowVersionId(UUID flowVersionId) { this.flowVersionId = flowVersionId; }

    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getVariables() { return variables; }
    public void setVariables(Map<String, Object> variables) { this.variables = variables; }

    public Instant getWaitUntil() { return waitUntil; }
    public void setWaitUntil(Instant waitUntil) { this.waitUntil = waitUntil; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getStartedAt() { return startedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
