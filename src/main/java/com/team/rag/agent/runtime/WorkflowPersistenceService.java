package com.team.rag.agent.runtime;

import com.team.rag.agent.routing.ExecutionStrategy;
import com.team.rag.monitoring.AgentBusinessMetrics;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/** Snapshot persistence plus exactly-once terminal metric bookkeeping. */
@Service
public class WorkflowPersistenceService {

    private final WorkflowSnapshotStore snapshotStore;
    private final WorkflowMetadataService metadataService;
    private final AgentBusinessMetrics metrics;

    public WorkflowPersistenceService(WorkflowSnapshotStore snapshotStore,
                                      WorkflowMetadataService metadataService,
                                      AgentBusinessMetrics metrics) {
        this.snapshotStore = snapshotStore;
        this.metadataService = metadataService;
        this.metrics = metrics;
    }

    public WorkflowRunSnapshot find(String workflowId) {
        return snapshotStore.find(workflowId);
    }

    public void put(WorkflowRunSnapshot snapshot) {
        snapshotStore.put(snapshot);
    }

    public void save(WorkflowRunSnapshot snapshot) {
        recordTerminalMetricOnce(snapshot);
        snapshotStore.save(snapshot);
    }

    private void recordTerminalMetricOnce(WorkflowRunSnapshot snapshot) {
        if (snapshot == null || !terminal(snapshot.status)) {
            return;
        }
        if (snapshot.metadata != null && Boolean.TRUE.equals(snapshot.metadata.get("terminalMetricRecorded"))) {
            return;
        }
        long latencyMs = snapshot.createdAt != null && snapshot.completedAt != null
                ? Math.max(0L, Duration.between(snapshot.createdAt, snapshot.completedAt).toMillis())
                : 0L;
        String strategy = snapshot.executionDecision != null && snapshot.executionDecision.strategy != null
                ? snapshot.executionDecision.strategy.name()
                : value(snapshot.engine, ExecutionStrategy.QUICK_QA.name());
        metrics.workflowTerminal(strategy, snapshot.status, latencyMs);
        snapshot.metadata = metadataService.mergeMetadata(snapshot.metadata, Map.of(
                "terminalMetricRecorded", true,
                "terminalLatencyMs", latencyMs));
    }

    private boolean terminal(String status) {
        if (status == null) return false;
        return switch (status.toUpperCase()) {
            case "COMPLETED", "FAILED", "INTERRUPTED", "WAITING_USER" -> true;
            default -> false;
        };
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
