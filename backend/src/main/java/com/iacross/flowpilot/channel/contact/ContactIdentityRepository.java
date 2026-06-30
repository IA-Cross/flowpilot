package com.iacross.flowpilot.channel.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ContactIdentityRepository extends JpaRepository<ContactIdentity, UUID> {

    @Query(value = """
            SELECT * FROM contact_identity
             WHERE tenant_id = :tenantId
               AND channel = :channel
               AND external_id = :externalId
             LIMIT 1
            """, nativeQuery = true)
    Optional<ContactIdentity> findByTenantChannelExternal(
            @Param("tenantId") UUID tenantId,
            @Param("channel")  String channel,
            @Param("externalId") String externalId);
}
