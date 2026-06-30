package com.iacross.flowpilot.shared.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-conversation distributed lock backed by Redis (TRD §5.4, §6.2).
 *
 * Guarantees single-writer processing: only one engine invocation advances a
 * conversation at a time, even under concurrent inbound messages.
 *
 * Uses SET NX PX for acquire and a Lua compare-and-delete for safe release
 * (prevents releasing another holder's lock). Virtual-thread-safe: retries use
 * Thread.sleep which parks the virtual thread, not the carrier thread (TRD §6.2).
 */
@Component
public class ConversationLock {

    private static final Logger log = LoggerFactory.getLogger(ConversationLock.class);
    private static final String KEY_PREFIX = "conv:lock:";
    private static final Duration LOCK_TTL  = Duration.ofSeconds(30);
    private static final int MAX_RETRIES    = 5;
    private static final long RETRY_DELAY_MS = 150;

    // Lua: compare-and-delete — only deletes if the stored value matches our lock id
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );

    private final StringRedisTemplate redis;

    public ConversationLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(UUID conversationId, String lockId) {
        String key = KEY_PREFIX + conversationId;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, lockId, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(UUID conversationId, String lockId) {
        String key = KEY_PREFIX + conversationId;
        redis.execute(RELEASE_SCRIPT, List.of(key), lockId);
    }

    /**
     * Acquire the lock (with retries), run action, release lock.
     * Retries using Thread.sleep — safe on virtual threads (parks, not blocks carrier).
     */
    public <T> T executeWithLock(UUID conversationId, Supplier<T> action) {
        String lockId = UUID.randomUUID().toString();
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (tryAcquire(conversationId, lockId)) {
                try {
                    return action.get();
                } finally {
                    release(conversationId, lockId);
                }
            }
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LockAcquisitionException("Interrupted while waiting for conversation lock", e);
                }
            }
        }
        log.warn("Could not acquire conversation lock for {} after {} attempts", conversationId, MAX_RETRIES);
        throw new LockAcquisitionException("Could not acquire conversation lock for: " + conversationId);
    }

    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String msg) { super(msg); }
        public LockAcquisitionException(String msg, Throwable cause) { super(msg, cause); }
    }
}
