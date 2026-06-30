package com.iacross.flowpilot.flow.domain;

import com.iacross.flowpilot.shared.id.UuidV7;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a flow graph. Once published, never mutated (ADR-006).
 * In-flight conversations keep a FK to the version they started on (flow_version_id).
 * The graph JSONB is deserialized to a FlowGraph record by Hibernate via Jackson.
 */
@Entity
@Table(name = "flow_version")
@EntityListeners(AuditingEntityListener.class)
public class FlowVersion {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    /** Lowercase: 'draft', 'published', 'archived'. */
    @Column(name = "state", nullable = false)
    private String state = "draft";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph", columnDefinition = "jsonb", nullable = false)
    private FlowGraph graph;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "published_at")
    private Instant publishedAt;

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

    public UUID getFlowId() { return flowId; }
    public void setFlowId(UUID flowId) { this.flowId = flowId; }

    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public FlowGraph getGraph() { return graph; }
    public void setGraph(FlowGraph graph) { this.graph = graph; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public Instant getCreatedAt() { return createdAt; }
}
