package com.iacross.flowpilot.shared.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TenantContext — verifies ThreadLocal semantics
 * (set/clear/isolation across threads).
 */
class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("set and get return the same UUID")
    void setAndGet() {
        UUID id = UUID.randomUUID();
        TenantContext.set(id);
        assertThat(TenantContext.get()).isEqualTo(id);
        assertThat(TenantContext.isPresent()).isTrue();
    }

    @Test
    @DisplayName("clear removes the value")
    void clear() {
        TenantContext.set(UUID.randomUUID());
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.isPresent()).isFalse();
    }

    @Test
    @DisplayName("context does not leak to a different thread")
    void noLeakAcrossThreads() throws InterruptedException {
        UUID mainTenantId = UUID.randomUUID();
        TenantContext.set(mainTenantId);

        AtomicReference<UUID> otherThreadValue = new AtomicReference<>();
        Thread other = new Thread(() -> otherThreadValue.set(TenantContext.get()));
        other.start();
        other.join();

        // The other thread must NOT see the main thread's tenant context
        assertThat(otherThreadValue.get()).isNull();
        // Main thread value is still intact
        assertThat(TenantContext.get()).isEqualTo(mainTenantId);
    }

    @Test
    @DisplayName("overwriting the context updates correctly")
    void overwrite() {
        UUID first  = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        TenantContext.set(first);
        TenantContext.set(second);
        assertThat(TenantContext.get()).isEqualTo(second);
    }
}
