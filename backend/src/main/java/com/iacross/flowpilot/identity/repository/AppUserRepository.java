package com.iacross.flowpilot.identity.repository;

import com.iacross.flowpilot.identity.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Find by email regardless of tenant context.
     * Used during login (before tenant context is established) so runs as superuser.
     */
    @Query(value = "SELECT * FROM app_user WHERE email = :email LIMIT 1",
           nativeQuery = true)
    Optional<AppUser> findByEmailNative(@Param("email") String email);

    boolean existsByEmail(String email);
}
