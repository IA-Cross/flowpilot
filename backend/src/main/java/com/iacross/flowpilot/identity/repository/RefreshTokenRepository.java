package com.iacross.flowpilot.identity.repository;

import com.iacross.flowpilot.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Query(value = "SELECT * FROM refresh_token WHERE token_hash = :hash LIMIT 1",
           nativeQuery = true)
    Optional<RefreshToken> findByTokenHash(@Param("hash") String hash);

    /** Revoke all active tokens for a user (logout-all). */
    @Modifying
    @Query(value = "UPDATE refresh_token SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL",
           nativeQuery = true)
    int revokeAllForUser(@Param("userId") UUID userId);

    /** Purge expired tokens (maintenance). */
    @Modifying
    @Query(value = "DELETE FROM refresh_token WHERE expires_at < :cutoff AND revoked_at IS NOT NULL",
           nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
