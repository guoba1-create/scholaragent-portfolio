package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores tool observations per workflow.
 *
 * <p>Persistence strategy:
 * <ul>
 *   <li>Redis list append for incremental writes.</li>
 *   <li>Local in-memory cache for fast reads.</li>
 *   <li>Best-effort fallback to memory-only mode when Redis is unavailable.</li>
 * </ul>
 */
@Service
public class ToolObservationStore {

    private static final Logger log = LoggerFactory.getLogger(ToolObservationStore.class);

    private static final String KEY_PREFIX = "workflow:tool-observations:";
    private static final Duration TTL = Duration.ofDays(7);

    // Local cache lifecycle.
    private static final Duration LOCAL_RETENTION = Duration.ofHours(12);
    private static final int MAX_LOCAL_WORKFLOWS = 1024;
    private static final long CLEANUP_INTERVAL = 64L;
    private static final long REDIS_RETRY_NANOS = Duration.ofSeconds(30).toNanos();

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private final Map<String, List<ToolObservation>> local = new ConcurrentHashMap<>();
    private final Map<String, Instant> localTouchedAt = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong();

    private volatile boolean redisAvailable = true;
    private volatile long nextRedisRetryAtNanos;

    public ToolObservationStore(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public void record(String workflowId, ToolObservation observation) {
        if (workflowId == null || workflowId.isBlank() || observation == null) {
            return;
        }

        List<ToolObservation> bucket = local.computeIfAbsent(
                workflowId, key -> Collections.synchronizedList(new ArrayList<>()));
        bucket.add(observation);
        touch(workflowId);
        appendRedis(workflowId, observation);
        maybeCleanupLocal();
    }

    public List<ToolObservation> getObservations(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return new ArrayList<>();
        }
        maybeCleanupLocal();

        List<ToolObservation> cached = local.get(workflowId);
        if (cached != null) {
            touch(workflowId);
            synchronized (cached) {
                return new ArrayList<>(cached);
            }
        }

        if (!canAttemptRedis()) {
            return new ArrayList<>();
        }
        try {
            List<ToolObservation> loaded = loadFromRedis(workflowId);
            if (loaded.isEmpty()) {
                return new ArrayList<>();
            }
            local.put(workflowId, Collections.synchronizedList(new ArrayList<>(loaded)));
            touch(workflowId);
            return new ArrayList<>(loaded);
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Tool observation Redis load cooling down for 30s | error={}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Replaces all observations for a workflow (used by projector/recovery paths).
     */
    public void setObservations(String workflowId, List<ToolObservation> observations) {
        if (workflowId == null || workflowId.isBlank()) {
            return;
        }
        List<ToolObservation> safe = Collections.synchronizedList(
                new ArrayList<>(observations == null ? List.of() : observations));
        local.put(workflowId, safe);
        touch(workflowId);
        replaceRedis(workflowId, safe);
        maybeCleanupLocal();
    }

    public void clear(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return;
        }
        local.remove(workflowId);
        localTouchedAt.remove(workflowId);
        if (canAttemptRedis()) {
            try {
                redisTemplate.delete(key(workflowId));
                markRedisHealthy();
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
        maybeCleanupLocal();
    }

    private void appendRedis(String workflowId, ToolObservation observation) {
        if (!canAttemptRedis()) {
            return;
        }
        try {
            String redisKey = key(workflowId);
            redisTemplate.opsForList().rightPush(redisKey, objectMapper.writeValueAsString(observation));
            redisTemplate.expire(redisKey, TTL);
            markRedisHealthy();
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Tool observation Redis append cooling down for 30s | error={}", e.getMessage());
        }
    }

    private void replaceRedis(String workflowId, List<ToolObservation> observations) {
        if (!canAttemptRedis()) {
            return;
        }
        try {
            String redisKey = key(workflowId);
            redisTemplate.delete(redisKey);
            synchronized (observations) {
                for (ToolObservation observation : observations) {
                    redisTemplate.opsForList().rightPush(redisKey, objectMapper.writeValueAsString(observation));
                }
            }
            redisTemplate.expire(redisKey, TTL);
            markRedisHealthy();
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Tool observation Redis replace cooling down for 30s | error={}", e.getMessage());
        }
    }

    private List<ToolObservation> loadFromRedis(String workflowId) throws Exception {
        List<String> rows = redisTemplate.opsForList().range(key(workflowId), 0, -1);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ToolObservation> loaded = new ArrayList<>(rows.size());
        for (String row : rows) {
            loaded.add(objectMapper.readValue(row, ToolObservation.class));
        }
        return loaded;
    }

    private void touch(String workflowId) {
        localTouchedAt.put(workflowId, Instant.now());
    }

    private void maybeCleanupLocal() {
        long count = accessCounter.incrementAndGet();
        if (count % CLEANUP_INTERVAL != 0) {
            return;
        }
        cleanupExpiredLocalEntries();
        enforceLocalCap();
    }

    private void cleanupExpiredLocalEntries() {
        Instant cutoff = Instant.now().minus(LOCAL_RETENTION);
        for (Map.Entry<String, Instant> entry : new ArrayList<>(localTouchedAt.entrySet())) {
            Instant touchedAt = entry.getValue();
            if (touchedAt != null && touchedAt.isBefore(cutoff)) {
                String workflowId = entry.getKey();
                local.remove(workflowId);
                localTouchedAt.remove(workflowId);
            }
        }
    }

    private void enforceLocalCap() {
        int overflow = local.size() - MAX_LOCAL_WORKFLOWS;
        if (overflow <= 0) {
            return;
        }
        List<Map.Entry<String, Instant>> ordered = new ArrayList<>(localTouchedAt.entrySet());
        ordered.sort(Comparator.comparing(Map.Entry::getValue));
        int toRemove = Math.min(overflow, ordered.size());
        for (int i = 0; i < toRemove; i++) {
            String workflowId = ordered.get(i).getKey();
            local.remove(workflowId);
            localTouchedAt.remove(workflowId);
        }
    }

    private String key(String workflowId) {
        return KEY_PREFIX + workflowId;
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
