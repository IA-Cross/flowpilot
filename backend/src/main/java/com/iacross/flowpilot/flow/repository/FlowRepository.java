package com.iacross.flowpilot.flow.repository;

import com.iacross.flowpilot.flow.domain.Flow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FlowRepository extends JpaRepository<Flow, UUID> {

    @Query(value = "SELECT * FROM flow WHERE id = :id AND tenant_id = :tenantId LIMIT 1", nativeQuery = true)
    Optional<Flow> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
