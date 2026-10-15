package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores complete tool payloads separately from prompt-facing observations.
 * Redis retains audit data for seven days; the bounded local cache is a short
 * availability fallback and never changes the public reference.
 */
@Service
public class RawToolPayloadStore {

    private static final Logger log = LoggerFactory.getLogger(RawToolPayloadStore.class);
    private static final String KEY_PREFIX = "workflow:tool-payload:";
    private static final String REF_PREFIX = "redis://tool-payload/";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Duration LOCAL_RETENTION = Duration.ofHours(12);
    private static final int MAX_LOCAL_PAYLOADS = 2_000;
    private static final long CLEANUP_INTERVAL = 64L;
    private static final long REDIS_RETRY_NANOS = Duration.ofSeconds(30).toNanos();

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, RawToolPayload> local = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong();
    private volatile boolean redisAvailable = true;
    private volatile long nextRedisRetryAtNanos;

    public RawToolPayloadStore(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public String store(String workflowId, String toolName, Object rawPayload) {
        String serialized = serialize(rawPayload);
        String payloadId = "payload_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        RawToolPayload record = new RawToolPayload();
        record.payloadRef = REF_PREFIX + payloadId;
        record.workflowId = workflowId;
        record.toolName = normalizeToolName(toolName);
        record.sha256 = sha256(serialized);
        record.payload = serialized;
        record.expiresAt = record.createdAt.plus(TTL);
        local.put(payloadId, record);
        persist(payloadId, record);
        cleanupIfDue();
        return record.payloadRef;
    }

    public Optional<RawToolPayload> find(String payloadRef) {
        String payloadId = payloadId(payloadRef);
        if (payloadId == null) {
            return Optional.empty();
        }
        cleanupIfDue();
        RawToolPayload cached = local.get(payloadId);
        if (cached != null && !expired(cached)) {
            return Optional.of(cached);
        }
        if (!canAttemptRedis()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key(payloadId));
            markRedisHealthy();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            RawToolPayload loaded = objectMapper.readValue(json, RawToolPayload.class);
            if (expired(loaded)) {
                return Optional.empty();
            }
            local.put(payloadId, loaded);
            return Optional.of(loaded);
        } catch (Exception error) {
            markRedisUnavailable();
            log.warn("Raw tool payload Redis read cooling down for 30s | error={}", error.getMessage());
            return Optional.empty();
        }
    }

    private void persist(String payloadId, RawToolPayload record) {
        if (!canAttemptRedis()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(payloadId), objectMapper.writeValueAsString(record), TTL);
            markRedisHealthy();
        } catch (Exception error) {
            markRedisUnavailable();
            log.warn("Raw tool payload Redis write cooling down for 30s | error={}", error.getMessage());
        }
    }

    private void cleanupIfDue() {
        if (accessCounter.incrementAndGet() % CLEANUP_INTERVAL != 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(LOCAL_RETENTION);
        local.entrySet().removeIf(entry -> entry.getValue() == null
                || expired(entry.getValue())
                || entry.getValue().createdAt.isBefore(cutoff));
        int overflow = local.size() - MAX_LOCAL_PAYLOADS;
        if (overflow <= 0) {
            return;
        }
        var ordered = new ArrayList<>(local.entrySet());
        ordered.sort(Comparator.comparing(entry -> entry.getValue().createdAt));
        for (int i = 0; i < Math.min(overflow, ordered.size()); i++) {
            local.remove(ordered.get(i).getKey());
        }
    }

    private boolean expired(RawToolPayload record) {
        return record.expiresAt != null && record.expiresAt.isBefore(Instant.now());
    }

    private String payloadId(String payloadRef) {
        if (payloadRef == null || !payloadRef.startsWith(REF_PREFIX)) {
            return null;
        }
        String id = payloadRef.substring(REF_PREFIX.length()).trim();
        return id.matches("payload_[a-f0-9]{16}") ? id : null;
    }

    private String key(String payloadId) {
        return KEY_PREFIX + payloadId;
    }

    private String serialize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return String.valueOf(value);
        }
    }

    private String normalizeToolName(String value) {
        return value == null || value.isBlank()
                ? "unknown_tool"
                : value.trim().toLowerCase().replace('-', '_');
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private boolean canAttemptRedis() {
        return redisAvailable || System.nanoTime() >= nextRedisRetryAtNanos;
    }

    private void markRedisHealthy() {
        redisAvailable = true;
        nextRedisRetryAtNanos = 0L;
    }

    private void markRedisUnavailable() {
        redisAvailable = false;
        nextRedisRetryAtNanos = System.nanoTime() + REDIS_RETRY_NANOS;
    }
}
