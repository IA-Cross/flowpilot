package com.iacross.flowpilot.flow.repository;

import com.iacross.flowpilot.flow.domain.FlowVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FlowVersionRepository extends JpaRepository<FlowVersion, UUID> {

    @Query(value = """
            SELECT MAX(version_no) FROM flow_version WHERE flow_id = :flowId
            """, nativeQuery = true)
    Optional<Integer> findMaxVersionNo(@Param("flowId") UUID flowId);
}
