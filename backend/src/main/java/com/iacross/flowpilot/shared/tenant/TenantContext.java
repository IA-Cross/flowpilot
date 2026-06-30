package com.iacross.flowpilot.shared.tenant;

import java.util.UUID;

/**
 * Thread-local holder for the current request's tenant identity.
 *
 * Populated by {@link com.iacross.flowpilot.shared.web.JwtAuthFilter} when a valid
 * JWT bearer token is present.  The RLS interceptor ({@link RlsTenantInterceptor})
 * reads this to issue {@code SET LOCAL app.tenant_id} at the start of each transaction.
 *
 * IMPORTANT: must be cleared at the end of every request (done in JwtAuthFilter finally block)
 * to avoid leaking context across virtual-thread-per-request reuse.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static UUID get() {
        return TENANT_ID.get();
    }

    public static boolean isPresent() {
        return TENANT_ID.get() != null;
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}
