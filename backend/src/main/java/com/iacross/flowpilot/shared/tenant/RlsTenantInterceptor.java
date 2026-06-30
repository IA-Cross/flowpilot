package com.iacross.flowpilot.shared.tenant;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Enforces PostgreSQL Row-Level Security by issuing
 * {@code SET LOCAL app.tenant_id = '<uuid>'} at the beginning of each
 * tenant-scoped transaction (TRD §5.2, §9).
 *
 * How it works:
 *  - Call {@link #applyToCurrentTransaction(JdbcTemplate)} inside any service method
 *    that requires tenant isolation, ideally at the start of the first
 *    {@code @Transactional} boundary.
 *  - The GUC is SESSION-LOCAL so it resets automatically when the transaction
 *    commits or rolls back — no explicit cleanup needed.
 *
 * Defence-in-depth layers (TRD §9):
 *  Layer 1 — repository-level: all repository queries include {@code WHERE tenant_id = ?}
 *  Layer 2 — PostgreSQL RLS: this interceptor sets the GUC so the DB enforces the policy
 */
@Component
public class RlsTenantInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RlsTenantInterceptor.class);

    /**
     * Issue {@code SET LOCAL app.tenant_id} on the current JDBC connection.
     * Must be called inside an active Spring transaction.
     */
    public static void applyToCurrentTransaction(JdbcTemplate jdbc) {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            log.warn("RLS: applyToCurrentTransaction called without a TenantContext — skipping SET LOCAL");
            return;
        }
        jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
    }

    /**
     * Register a before-commit synchronization that ensures SET LOCAL runs
     * within the transaction even if not called explicitly by the service.
     * Call once at transaction open (e.g., from an AOP aspect or service base class).
     */
    public static void registerSynchronization(JdbcTemplate jdbc) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                applyToCurrentTransaction(jdbc);
            }
        });
    }
}
