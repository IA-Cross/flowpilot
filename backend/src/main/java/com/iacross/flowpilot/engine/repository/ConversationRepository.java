package com.iacross.flowpilot.engine.repository;

import com.iacross.flowpilot.engine.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * Find the most recent non-ended conversation for a given contact+channel.
     * Used by the engine to resume an existing conversation rather than starting fresh.
     * Ordered by started_at DESC so we get the newest.
     */
    @Query(value = """
            SELECT * FROM conversation
             WHERE contact_id = :contactId
               AND channel_connection_id = :channelConnectionId
               AND status NOT IN ('ended','handoff')
             ORDER BY started_at DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<Conversation> findActiveByContactAndChannel(@Param("contactId") UUID contactId,
                                                         @Param("channelConnectionId") UUID channelConnectionId);

    /**
     * Privileged (pre-RLS) lookup by id — used by ConversationStateService to rehydrate
     * from PG when Redis hot state is absent after a restart.
     * Native query bypasses the RLS SET LOCAL applied by applyToCurrentTransaction.
     */
    @Query(value = "SELECT * FROM conversation WHERE id = :id", nativeQuery = true)
    Optional<Conversation> findByIdPrivileged(@Param("id") UUID id);
}
