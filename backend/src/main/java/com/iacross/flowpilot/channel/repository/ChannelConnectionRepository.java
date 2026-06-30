package com.iacross.flowpilot.channel.repository;

import com.iacross.flowpilot.channel.domain.ChannelConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ChannelConnectionRepository extends JpaRepository<ChannelConnection, UUID> {

    /** Tenant-scoped lookup by ID for authenticated management API. */
    Optional<ChannelConnection> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Privileged lookup by channel connection ID without tenant context.
     * Used by the Telegram webhook controller BEFORE TenantContext is set,
     * to resolve tenant_id from the incoming botRef path variable.
     */
    @Query(value = "SELECT * FROM channel_connection WHERE id = :id LIMIT 1", nativeQuery = true)
    Optional<ChannelConnection> findByIdForWebhook(@Param("id") UUID id);
}
