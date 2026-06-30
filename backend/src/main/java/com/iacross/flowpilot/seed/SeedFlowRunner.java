package com.iacross.flowpilot.seed;

import com.iacross.flowpilot.flow.domain.FlowEdge;
import com.iacross.flowpilot.flow.domain.FlowGraph;
import com.iacross.flowpilot.flow.domain.FlowNode;
import com.iacross.flowpilot.flow.service.FlowService;
import com.iacross.flowpilot.identity.domain.Tenant;
import com.iacross.flowpilot.identity.repository.TenantRepository;
import com.iacross.flowpilot.shared.tenant.RlsTenantInterceptor;
import com.iacross.flowpilot.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Seeds a demo flow for local development.
 *
 * Flow: trigger → send_message(greeting) → collect_input(name)
 *       → condition(name not_empty) → send_message(personalized) → End
 *                                   ↘ send_message(fallback) → End
 *
 * Only runs under @Profile("local") so it never touches staging/prod.
 * Idempotent: skips seed if a flow named "Demo Survey" already exists.
 */
@Component
@Profile("local")
public class SeedFlowRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedFlowRunner.class);
    private static final String SEED_FLOW_NAME = "Demo Survey";

    private final FlowService flowService;
    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;

    public SeedFlowRunner(FlowService flowService, TenantRepository tenants, JdbcTemplate jdbc) {
        this.flowService = flowService;
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Tenant tenant = tenants.findAll().stream().findFirst().orElse(null);
        if (tenant == null) {
            log.info("SeedFlowRunner: no tenant found — skipping seed");
            return;
        }

        TenantContext.set(tenant.getId());
        try {
            RlsTenantInterceptor.applyToCurrentTransaction(jdbc);

            // Idempotency: skip if already seeded
            boolean exists = Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM flow WHERE tenant_id = ? AND name = ?)",
                    Boolean.class, tenant.getId(), SEED_FLOW_NAME));
            if (exists) {
                log.info("SeedFlowRunner: '{}' already seeded — skipping", SEED_FLOW_NAME);
                return;
            }

            flowService.createAndPublish(tenant.getId(), SEED_FLOW_NAME, buildDemoGraph());
            log.info("SeedFlowRunner: seeded flow '{}' for tenant {}", SEED_FLOW_NAME, tenant.getId());
        } finally {
            TenantContext.clear();
        }
    }

    private FlowGraph buildDemoGraph() {
        var trigger     = node("n1", "trigger",      Map.of());
        var greet       = node("n2", "send_message",  Map.of("text", "Hello! What's your name?"));
        var collect     = node("n3", "collect_input", Map.of("variableName", "name"));
        var condition   = node("n4", "condition",     Map.of(
                "variable",  "name",
                "operator",  "not_empty",
                "trueEdge",  "yes",
                "falseEdge", "no"));
        var personalized = node("n5", "send_message", Map.of("text", "Nice to meet you, {{name}}! Thanks for chatting."));
        var fallback    = node("n6", "send_message",  Map.of("text", "No worries! Thanks for chatting."));

        var e1 = edge("e1", "n1", "n2", null);
        var e2 = edge("e2", "n2", "n3", null);
        var e3 = edge("e3", "n3", "n4", null);
        var e4 = edge("e4", "n4", "n5", "yes");
        var e5 = edge("e5", "n4", "n6", "no");

        return new FlowGraph(
            List.of(trigger, greet, collect, condition, personalized, fallback),
            List.of(e1, e2, e3, e4, e5)
        );
    }

    private FlowNode node(String id, String type, Map<String, Object> config) {
        return new FlowNode(id, type, config, Map.of("x", 0, "y", 0));
    }

    private FlowEdge edge(String id, String source, String target, String sourceHandle) {
        return new FlowEdge(id, source, target, sourceHandle);
    }
}
