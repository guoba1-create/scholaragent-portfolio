package com.team.rag.agent.runtime;

import com.team.rag.agent.grpc.AgentGrpcClient;
import com.team.rag.agent.grpc.AgentWorkflowProto;
import com.team.rag.agent.trace.AgentTraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/** Converts and applies Python terminal results to the authoritative Java snapshot. */
@Service
public class WorkflowResultApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowResultApplicationService.class);

    private final WorkflowPersistenceService persistence;
    private final WorkflowPythonResultProjector projector;
    private final WorkflowMetadataService metadataService;
    private final WorkflowPostProcessingService postProcessing;
    private final WorkflowEventBus eventBus;
    private final AgentTraceService traceService;
    private final WorkflowSnapshotHydrator hydrator;

    public WorkflowResultApplicationService(WorkflowPersistenceService persistence,
                                            WorkflowPythonResultProjector projector,
                                            WorkflowMetadataService metadataService,
                                            WorkflowPostProcessingService postProcessing,
                                            WorkflowEventBus eventBus,
                                            AgentTraceService traceService,
                                            WorkflowSnapshotHydrator hydrator) {
        this.persistence = persistence;
        this.projector = projector;
        this.metadataService = metadataService;
        this.postProcessing = postProcessing;
        this.eventBus = eventBus;
        this.traceService = traceService;
        this.hydrator = hydrator;
    }

    public void apply(String workflowId, WorkflowResultV1 result) {
        WorkflowRunSnapshot snapshot = persistence.find(workflowId);
        if (snapshot == null) {
            log.warn("Cannot apply Python result: snapshot not found | workflowId={}", workflowId);
            return;
        }
        String status = normalizeStatus(result == null ? null : result.status);
        snapshot.status = status;
        snapshot.finalAnswer = result == null ? null : result.finalAnswer;
        snapshot.errorMessage = result == null ? "Python runtime returned no result" : result.errorMessage;
        snapshot.completedAt = Instant.now();
        snapshot.runtimeProvider = "python-langgraph";
        snapshot.runtimeTarget = "python";

        WorkflowPythonResultProjector.Projection projection = projector.project(workflowId, result);
        applyProjection(snapshot, projection);
        snapshot.metadata = metadataService.mergeMetadata(snapshot.metadata, Map.of(
                "lastTerminalStatus", status,
                "lastTerminalAt", Instant.now().toString(),
                "runtimeTarget", "python",
                "controlPending", ""));
        Map<String, Object> meta = snapshot.metadata;
        if (meta != null) {
            postProcessing.captureDiagnosticsIfNeeded(
                    string(meta, "userId", "user-001"),
                    string(meta, "researchGroupId", "replace-me"),
                    string(meta, "message", ""), snapshot, snapshot.finalAnswer);
        }

        String terminalEvent = "FAILED".equals(status) ? "WORKFLOW_FAILED"
                : "WAITING_USER".equals(status) ? "AWAITING_CONFIRMATION" : "WORKFLOW_DONE";
        String summary = snapshot.finalAnswer != null && !snapshot.finalAnswer.isBlank()
                ? preview(snapshot.finalAnswer)
                : value(snapshot.errorMessage, "Python runtime completed");
        eventBus.publish(workflowId, terminalEvent, "workflow", status, summary);

        String runtimeLabel = projection.runtimeMetadata != null && projection.runtimeMetadata.containsKey("runtime")
                ? String.valueOf(projection.runtimeMetadata.get("runtime")) : "langgraph";
        if (!projection.tracePayloadApplied) {
            traceService.finishWorkflow(workflowId, status, "Python runtime (" + runtimeLabel + ")");
        }
        persistence.save(snapshot);
        hydrator.hydrate(snapshot);

        if ("COMPLETED".equals(status) && meta != null && meta.containsKey("userId")
                && snapshot.finalAnswer != null) {
            postProcessing.queueMemoryWriteback(
                    string(meta, "userId", "user-001"),
                    string(meta, "researchGroupId", "replace-me"),
                    string(meta, "conversationId", workflowId), workflowId,
                    string(meta, "message", ""), snapshot.finalAnswer, snapshot.routeDecision);
        }
        log.info("Python result applied | workflowId={}, status={}", workflowId, status);
    }

    public void applyGrpc(String workflowId, AgentWorkflowProto.WorkflowEventMsg msg) {
        WorkflowResultV1 result = new WorkflowResultV1();
        result.workflowId = workflowId;
        result.status = msg.getStatus();
        result.finalAnswer = msg.getFinalAnswer();
        result.errorMessage = msg.getErrorMessage();
        result.reactSteps = AgentGrpcClient.structListToMapList(msg.getReactStepsList());
        result.traces = AgentGrpcClient.structListToMapList(msg.getTracesList());
        result.messages = AgentGrpcClient.structListToMapList(msg.getMessagesList());
        result.toolObservations = AgentGrpcClient.structListToMapList(msg.getToolObservationsList());
        result.documentRefs = AgentGrpcClient.structListToMapList(msg.getDocumentRefsList());
        result.retrievalDiagnostics = AgentGrpcClient.structListToMapList(msg.getRetrievalDiagnosticsList());
        if (msg.hasClaimEvidenceTable()) result.claimEvidenceTable = AgentGrpcClient.structToMap(msg.getClaimEvidenceTable());
        if (msg.hasQualityReport()) result.qualityReport = AgentGrpcClient.structToMap(msg.getQualityReport());
        result.warnings = new ArrayList<>(msg.getWarningsList());
        if (msg.hasStats()) result.stats = AgentGrpcClient.structToMap(msg.getStats());
        if (msg.hasResultMetadata()) result.metadata = AgentGrpcClient.structToMap(msg.getResultMetadata());
        apply(workflowId, result);
    }

    private void applyProjection(WorkflowRunSnapshot snapshot, WorkflowPythonResultProjector.Projection value) {
        if (value.documentRefs != null) snapshot.documentRefs = value.documentRefs;
        if (value.retrievalDiagnostics != null) snapshot.retrievalDiagnostics = value.retrievalDiagnostics;
        if (value.claimEvidenceTable != null) snapshot.claimEvidenceTable = value.claimEvidenceTable;
        if (value.qualityReport != null) snapshot.qualityReport = value.qualityReport;
        if (value.warnings != null) snapshot.warnings = value.warnings;
        if (value.runtimeMetadata != null) {
            snapshot.runtimeMetadata = metadataService.mergeMetadata(snapshot.runtimeMetadata, value.runtimeMetadata);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "FAILED";
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED", "SUCCESS" -> "COMPLETED";
            case "ASK_USER", "WAITING_USER", "AWAITING_CONFIRMATION" -> "WAITING_USER";
            case "INTERRUPTED" -> "INTERRUPTED";
            case "RUNNING" -> "RUNNING";
            default -> "FAILED";
        };
    }

    private String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String preview(String text) {
        return text == null || text.length() <= 300 ? value(text, "") : text.substring(0, 300) + "...";
    }
}
