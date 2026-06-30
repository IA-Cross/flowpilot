package com.iacross.flowpilot.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.engine.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages conversation hot-state in Redis (ADR-004).
 *
 * Read path:  Redis hit → return immediately; Redis miss → load from PG (restart recovery).
 * Write path: save to PG first (authoritative), then write JSON snapshot to Redis.
 *
 * Redis key format: conv:state:{conversationId}  TTL: 1 hour
 */
@Service
public class ConversationStateService {

    private static final Logger log = LoggerFactory.getLogger(ConversationStateService.class);
    private static final String KEY_PREFIX = "conv:state:";
    private static final Duration STATE_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final ConversationRepository conversations;
    private final ObjectMapper objectMapper;

    public ConversationStateService(StringRedisTemplate redis,
                                    ConversationRepository conversations,
                                    ObjectMapper objectMapper) {
        this.redis = redis;
        this.conversations = conversations;
        this.objectMapper = objectMapper;
    }

    /**
     * Load conversation: Redis hot state first, PG fallback on cache miss.
     * This is what makes conversation state survive a backend restart.
     */
    public Optional<Conversation> load(UUID conversationId) {
        String key = KEY_PREFIX + conversationId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                return Optional.of(objectMapper.readValue(cached, Conversation.class));
            } catch (Exception e) {
                log.warn("Failed to deserialize cached conversation {}, falling back to PG", conversationId, e);
                redis.delete(key);
            }
        }
        // PG fallback (restart recovery)
        Optional<Conversation> conv = conversations.findByIdPrivileged(conversationId);
        conv.ifPresent(c -> writeToCache(key, c));
        return conv;
    }

    /**
     * Persist conversation to PG (authoritative) and refresh the Redis hot copy.
     * Callers must be inside a transaction with RLS already applied.
     */
    public Conversation save(Conversation conversation) {
        Conversation saved = conversations.save(conversation);
        writeToCache(KEY_PREFIX + saved.getId(), saved);
        return saved;
    }

    /** Evict the Redis hot copy (e.g. after conversation ends or on error). */
    public void evict(UUID conversationId) {
        redis.delete(KEY_PREFIX + conversationId);
    }

    private void writeToCache(String key, Conversation conversation) {
        try {
            String json = objectMapper.writeValueAsString(conversation);
            redis.opsForValue().set(key, json, STATE_TTL);
        } catch (Exception e) {
            // Cache write failure is non-fatal; PG remains authoritative
            log.warn("Failed to cache conversation state for {}", conversation.getId(), e);
        }
    }
}
