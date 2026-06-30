package com.iacross.flowpilot.shared;

import com.iacross.flowpilot.shared.lock.ConversationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationLockTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ConversationLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lock = new ConversationLock(redis);
    }

    @Test
    void executeWithLock_acquiresAndReleases() {
        UUID convId = UUID.randomUUID();
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        AtomicBoolean ran = new AtomicBoolean(false);
        lock.executeWithLock(convId, () -> { ran.set(true); return null; });

        assertThat(ran).isTrue();
        verify(valueOps).setIfAbsent(contains(convId.toString()), anyString(), any());
        verify(redis).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void executeWithLock_returnsActionResult() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        String result = lock.executeWithLock(UUID.randomUUID(), () -> "hello");
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void executeWithLock_retriesOnContention_thenSucceeds() {
        UUID convId = UUID.randomUUID();
        // First two attempts fail (lock held), third succeeds
        when(valueOps.setIfAbsent(anyString(), anyString(), any()))
            .thenReturn(false)
            .thenReturn(false)
            .thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        AtomicBoolean ran = new AtomicBoolean(false);
        lock.executeWithLock(convId, () -> { ran.set(true); return null; });
        assertThat(ran).isTrue();
    }

    @Test
    void executeWithLock_exhaustedRetries_throwsLockAcquisitionException() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> lock.executeWithLock(UUID.randomUUID(), () -> null))
            .isInstanceOf(ConversationLock.LockAcquisitionException.class);
    }

    @Test
    void lockKey_containsConversationId() {
        UUID convId = UUID.randomUUID();
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        lock.executeWithLock(convId, () -> null);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(keyCaptor.capture(), anyString(), any());
        assertThat(keyCaptor.getValue()).contains(convId.toString());
    }
}
