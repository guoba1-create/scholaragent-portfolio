package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.infrastructure.RedisRetryGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

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
 * Workflow event bus with replay + live stream support.
 */
@Component
public class WorkflowEventBus {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventBus.class);

    private static final Duration REDIS_TTL = Duration.ofDays(7);
    private static final Duration LOCAL_TERMINAL_RETENTION = Duration.ofHours(6);
    private static final int MAX_LOCAL_EVENTS_PER_WORKFLOW = 500;
    private static final int MAX_LOCAL_WORKFLOWS = 1500;
    private static final long CLEANUP_INTERVAL = 64L;

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private final Map<String, List<WorkflowEvent>> eventHistory = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<WorkflowEvent>> sinks = new ConcurrentHashMap<>();
    private final Map<String, Instant> localTouchedAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> terminalAt = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong();

    private final RedisRetryGate redisGate = new RedisRetryGate(Duration.ofSeconds(30));

    public WorkflowEventBus(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public WorkflowEvent publish(String workflowId, String eventType, String phase,
                                 String status, String summary) {
        return publish(workflowId, eventType, phase, null, null, status, summary, Map.of());
    }

    public WorkflowEvent publish(String workflowId, String eventType, String phase,
                                 String agentName, String toolName,
                                 String status, String summary, Map<String, Object> payload) {
        WorkflowEvent event = new WorkflowEvent(workflowId, eventType, phase, status, summary);
        event.setAgentName(agentName);
        event.setToolName(toolName);
        if (payload != null) {
            event.setPayload(payload);
        }
        publish(event);
        return event;
    }

    public void publish(WorkflowEvent event) {
        if (event == null || event.getWorkflowId() == null || event.getWorkflowId().isBlank()) {
            return;
        }
        String workflowId = event.getWorkflowId();
        Sinks.Many<WorkflowEvent> sink = sinkFor(workflowId);

        appendLocalEvent(workflowId, event);
        persist(event);
        sink.tryEmitNext(event);

        if (isTerminalEvent(event.getEventType())) {
            terminalAt.put(workflowId, event.getCreatedAt() == null ? Instant.now() : event.getCreatedAt());
            sink.tryEmitComplete();
        } else {
            terminalAt.remove(workflowId);
        }
        maybeCleanupLocal();
    }

    public Flux<WorkflowEvent> stream(String workflowId) {
        maybeCleanupLocal();
        List<WorkflowEvent> replay = getEvents(workflowId);
        return Flux.concat(Flux.fromIterable(replay), sinkFor(workflowId).asFlux());
    }

    public List<WorkflowEvent> getEvents(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return List.of();
        }
        maybeCleanupLocal();

        List<WorkflowEvent> local = eventHistory.get(workflowId);
        if (local != null && !local.isEmpty()) {
            touch(workflowId);
            synchronized (local) {
                return new ArrayList<>(local);
            }
        }

        List<WorkflowEvent> persisted = loadPersisted(workflowId);
        if (!persisted.isEmpty()) {
            List<WorkflowEvent> safe = Collections.synchronizedList(new ArrayList<>(persisted));
            trimLocalHistory(safe);
            eventHistory.put(workflowId, safe);
            touch(workflowId);
            WorkflowEvent last = persisted.get(persisted.size() - 1);
            if (isTerminalEvent(last.getEventType())) {
                terminalAt.put(workflowId, last.getCreatedAt() == null ? Instant.now() : last.getCreatedAt());
            }
        }
        return persisted;
    }

    private void appendLocalEvent(String workflowId, WorkflowEvent event) {
        List<WorkflowEvent> history = eventHistory.computeIfAbsent(
                workflowId, key -> Collections.synchronizedList(new ArrayList<>()));
        history.add(event);
        trimLocalHistory(history);
        touch(workflowId);
    }

    private void trimLocalHistory(List<WorkflowEvent> history) {
        synchronized (history) {
            while (history.size() > MAX_LOCAL_EVENTS_PER_WORKFLOW) {
                history.remove(0);
            }
        }
    }

    private void persist(WorkflowEvent event) {
        if (!redisGate.canAttempt()) {
            return;
        }
        try {
            String key = key(event.getWorkflowId());
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(event));
            redisTemplate.expire(key, REDIS_TTL);
            redisGate.success();
        } catch (Exception e) {
            redisGate.failure();
            log.warn("Workflow event Redis persistence cooling down for 30s | error={}", e.getMessage());
        }
    }

    private List<WorkflowEvent> loadPersisted(String workflowId) {
        if (!redisGate.canAttempt()) {
            return List.of();
        }
        try {
            List<String> rows = redisTemplate.opsForList().range(key(workflowId), 0, -1);
            if (rows == null || rows.isEmpty()) {
                redisGate.success();
                return List.of();
            }
            List<WorkflowEvent> events = new ArrayList<>(rows.size());
            for (String row : rows) {
                events.add(objectMapper.readValue(row, WorkflowEvent.class));
            }
            redisGate.success();
            return events;
        } catch (Exception e) {
            redisGate.failure();
            log.warn("Workflow event Redis replay cooling down for 30s | error={}", e.getMessage());
            return List.of();
        }
    }

    private Sinks.Many<WorkflowEvent> sinkFor(String workflowId) {
        return sinks.computeIfAbsent(workflowId, key -> Sinks.many().multicast().directBestEffort());
    }

    private boolean isTerminalEvent(String eventType) {
        return "WORKFLOW_DONE".equals(eventType) || "WORKFLOW_FAILED".equals(eventType);
    }

    private void touch(String workflowId) {
        localTouchedAt.put(workflowId, Instant.now());
    }

    private void maybeCleanupLocal() {
        long count = accessCounter.incrementAndGet();
        if (count % CLEANUP_INTERVAL != 0) {
            return;
        }
        cleanupExpiredTerminalWorkflows();
        enforceWorkflowCap();
    }

    private void cleanupExpiredTerminalWorkflows() {
        Instant cutoff = Instant.now().minus(LOCAL_TERMINAL_RETENTION);
        for (Map.Entry<String, Instant> entry : new ArrayList<>(terminalAt.entrySet())) {
            Instant endedAt = entry.getValue();
            if (endedAt != null && endedAt.isBefore(cutoff)) {
                removeLocalWorkflow(entry.getKey());
            }
        }
    }

    private void enforceWorkflowCap() {
        int overflow = eventHistory.size() - MAX_LOCAL_WORKFLOWS;
        if (overflow <= 0) {
            return;
        }

        List<Map.Entry<String, Instant>> terminalOrdered = new ArrayList<>(terminalAt.entrySet());
        terminalOrdered.sort(Comparator.comparing(Map.Entry::getValue));
        int removed = 0;
        for (Map.Entry<String, Instant> entry : terminalOrdered) {
            if (removed >= overflow) {
                break;
            }
            removeLocalWorkflow(entry.getKey());
            removed++;
        }
        if (removed >= overflow) {
            return;
        }

        List<Map.Entry<String, Instant>> touchedOrdered = new ArrayList<>(localTouchedAt.entrySet());
        touchedOrdered.sort(Comparator.comparing(Map.Entry::getValue));
        for (Map.Entry<String, Instant> entry : touchedOrdered) {
            if (removed >= overflow) {
                break;
            }
            String workflowId = entry.getKey();
            if (eventHistory.containsKey(workflowId)) {
                removeLocalWorkflow(workflowId);
                removed++;
            }
        }
    }

    private void removeLocalWorkflow(String workflowId) {
        eventHistory.remove(workflowId);
        terminalAt.remove(workflowId);
        localTouchedAt.remove(workflowId);
        Sinks.Many<WorkflowEvent> sink = sinks.remove(workflowId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    private String key(String workflowId) {
        return "workflow:events:" + workflowId;
    }
}
