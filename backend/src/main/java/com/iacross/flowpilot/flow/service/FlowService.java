package com.iacross.flowpilot.flow.service;

import com.iacross.flowpilot.flow.domain.Flow;
import com.iacross.flowpilot.flow.domain.FlowGraph;
import com.iacross.flowpilot.flow.domain.FlowVersion;
import com.iacross.flowpilot.flow.repository.FlowRepository;
import com.iacross.flowpilot.flow.repository.FlowVersionRepository;
import com.iacross.flowpilot.shared.tenant.RlsTenantInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class FlowService {

    private final FlowRepository flows;
    private final FlowVersionRepository versions;
    private final JdbcTemplate jdbc;

    public FlowService(FlowRepository flows, FlowVersionRepository versions, JdbcTemplate jdbc) {
        this.flows = flows;
        this.versions = versions;
        this.jdbc = jdbc;
    }

    /**
     * Create and immediately publish a flow with the given graph.
     * Used by SeedFlowRunner and tests. Returns the published FlowVersion.
     */
    public FlowVersion createAndPublish(UUID tenantId, String name, FlowGraph graph) {
        RlsTenantInterceptor.applyToCurrentTransaction(jdbc);

        var flow = new Flow();
        flow.setTenantId(tenantId);
        flow.setName(name);
        flow.setStatus("active");
        flows.save(flow);

        var version = new FlowVersion();
        version.setTenantId(tenantId);
        version.setFlowId(flow.getId());
        version.setVersionNo(1);
        version.setState("published");
        version.setGraph(graph);
        version.setPublishedAt(Instant.now());
        versions.save(version);

        flow.setPublishedVersionId(version.getId());
        flows.save(flow);

        return version;
    }

    /** Load the published version of a flow — pinned to conversations that start on it. */
    public FlowVersion getPublishedVersion(UUID flowId, UUID tenantId) {
        RlsTenantInterceptor.applyToCurrentTransaction(jdbc);
        Flow flow = flows.findByIdAndTenantId(flowId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        if (flow.getPublishedVersionId() == null) {
            throw new IllegalStateException("Flow has no published version: " + flowId);
        }
        return versions.findById(flow.getPublishedVersionId())
            .orElseThrow(() -> new IllegalStateException("Published version not found"));
    }
}
