package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable workflow snapshot store with an in-process hot cache.
 *
 * <p>The store owns Redis serialization, retry cooldown, TTL and bounded local retention.
 * Runtime orchestration only mutates snapshots and asks this component to persist them.</p>
 */
@Service
public class WorkflowSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSnapshotStore.class);
    private static final Duration SNAPSHOT_TTL = Duration.ofDays(7);
    private static final Duration LOCAL_RETENTION = Duration.ofHours(12);
    private static final int MAX_LOCAL_SNAPSHOTS = 1200;
    private static final long CLEANUP_INTERVAL = 64L;
    private static final long REDIS_RETRY_NANOS = Duration.ofSeconds(30).toNanos();

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, WorkflowRunSnapshot> local = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong();
    private volatile boolean redisAvailable = true;
    private volatile long nextRedisRetryAtNanos;

    public WorkflowSnapshotStore(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public WorkflowRunSnapshot find(String workflowId) {
        cleanupIfDue();
        WorkflowRunSnapshot snapshot = local.get(workflowId);
        if (snapshot != null) {
            return snapshot;
        }
        snapshot = loadFromRedis(workflowId);
        if (snapshot != null) {
            local.put(workflowId, snapshot);
        }
        return snapshot;
    }

    public void put(WorkflowRunSnapshot snapshot) {
        if (snapshot == null || snapshot.workflowId == null) {
            return;
        }
        local.put(snapshot.workflowId, snapshot);
        cleanupIfDue();
    }

    public void save(WorkflowRunSnapshot snapshot) {
        put(snapshot);
        if (snapshot == null || snapshot.workflowId == null || !canAttemptRedis()) {
            return;
        }
        try {
            String key = key(snapshot.workflowId);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(snapshot));
            redisTemplate.expire(key, SNAPSHOT_TTL);
            markRedisHealthy();
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Workflow snapshot Redis persistence cooling down for 30s | error={}", e.getMessage());
        }
    }

    public boolean isRedisAvailable() {
        return redisAvailable;
    }

    int localSize() {
        return local.size();
    }

    private WorkflowRunSnapshot loadFromRedis(String workflowId) {
        if (workflowId == null || !canAttemptRedis()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key(workflowId));
            markRedisHealthy();
            return json == null || json.isBlank()
                    ? null
                    : objectMapper.readValue(json, WorkflowRunSnapshot.class);
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Workflow snapshot Redis load cooling down for 30s | error={}", e.getMessage());
            return null;
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

    private void cleanupIfDue() {
        if (accessCounter.incrementAndGet() % CLEANUP_INTERVAL != 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(LOCAL_RETENTION);
        local.entrySet().removeIf(entry -> {
            WorkflowRunSnapshot snapshot = entry.getValue();
            if (snapshot == null || !isTerminal(snapshot.status)) {
                return false;
            }
            Instant terminalAt = snapshot.completedAt != null ? snapshot.completedAt : snapshot.createdAt;
            return terminalAt != null && terminalAt.isBefore(cutoff);
        });
        enforceCap();
    }

    private void enforceCap() {
        int overflow = local.size() - MAX_LOCAL_SNAPSHOTS;
        if (overflow <= 0) {
            return;
        }
        List<Map.Entry<String, WorkflowRunSnapshot>> entries = new ArrayList<>(local.entrySet());
        entries.sort(Comparator
                .comparing((Map.Entry<String, WorkflowRunSnapshot> entry) ->
                        entry.getValue() != null && isTerminal(entry.getValue().status) ? 0 : 1)
                .thenComparing(entry -> timestamp(entry.getValue())));
        for (int i = 0; i < Math.min(overflow, entries.size()); i++) {
            local.remove(entries.get(i).getKey());
        }
    }

    private Instant timestamp(WorkflowRunSnapshot snapshot) {
        if (snapshot == null) {
            return Instant.MIN;
        }
        if (snapshot.completedAt != null) {
            return snapshot.completedAt;
        }
        return snapshot.createdAt == null ? Instant.MIN : snapshot.createdAt;
    }

    private boolean isTerminal(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED", "FAILED", "INTERRUPTED", "WAITING_USER", "UNKNOWN" -> true;
            default -> false;
        };
    }

    private String key(String workflowId) {
        return "workflow:snapshot:" + workflowId;
    }
}
