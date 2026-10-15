package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.context.ContextEnvelope;
import com.team.rag.agent.grpc.AgentGrpcClient;
import com.team.rag.agent.grpc.AgentWorkflowProto;
import com.team.rag.agent.routing.ExecutionCatalogService;
import com.team.rag.agent.routing.HybridRouteDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the Java/Python gRPC boundary, including durable resume dispatch. */
@Service
public class PythonWorkflowDispatchService {

    private static final Logger log = LoggerFactory.getLogger(PythonWorkflowDispatchService.class);

    private final AgentGrpcClient grpcClient;
    private final WorkflowEventBus eventBus;
    private final WorkflowPersistenceService persistenceService;
    private final WorkflowMetadataService metadataService;
    private final WorkflowResultApplicationService resultApplicationService;
    private final WorkflowSnapshotHydrator snapshotHydrator;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;

    public PythonWorkflowDispatchService(
            AgentGrpcClient grpcClient,
            WorkflowEventBus eventBus,
            WorkflowPersistenceService persistenceService,
            WorkflowMetadataService metadataService,
            WorkflowResultApplicationService resultApplicationService,
            WorkflowSnapshotHydrator snapshotHydrator,
            ObjectMapper objectMapper,
            @Qualifier("workflowTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.grpcClient = grpcClient;
        this.eventBus = eventBus;
        this.persistenceService = persistenceService;
        this.metadataService = metadataService;
        this.resultApplicationService = resultApplicationService;
        this.snapshotHydrator = snapshotHydrator;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public void dispatchInitial(
            String workflowId,
            WorkflowRunRequest request,
            HybridRouteDecision decision,
            MemoryRecallRecord recall,
            ContextEnvelope contextEnvelope,
            WorkflowRunSnapshot snapshot) {
        AgentWorkflowProto.RunWorkflowRequest grpcRequest =
                buildRequest(workflowId, request, decision, recall, contextEnvelope);
        snapshot.runtimeProvider = "python-langgraph";
        snapshot.runtimeTarget = "python";
        snapshot.status = "RUNNING";
        snapshot.metadata = metadataService.mergeMetadata(snapshot.metadata, Map.of(
                "runtimeTarget", "python",
                "dispatchedAt", Instant.now().toString()));
        persistenceService.save(snapshot);
        eventBus.publish(workflowId, "WORKFLOW_STARTED", "workflow", "RUNNING",
                "Dispatched to Python runtime via gRPC");
        grpcClient.runWorkflow(
                grpcRequest,
                eventBus,
                terminal -> resultApplicationService.applyGrpc(workflowId, terminal),
                600);
    }

    public void resume(String workflowId, String resumeMode) {
        WorkflowRunSnapshot snapshot = persistenceService.find(workflowId);
        if (snapshot == null || "UNKNOWN".equalsIgnoreCase(snapshot.status)) {
            log.warn("Cannot resume: snapshot not found | workflowId={}", workflowId);
            return;
        }
        executor.submit(() -> resumeAsync(workflowId, defaultString(resumeMode, "checkpoint"), snapshot));
    }

    private void resumeAsync(String workflowId, String resumeMode, WorkflowRunSnapshot snapshot) {
        try {
            snapshot.status = "RUNNING";
            snapshot.runtimeTarget = "python";
            snapshot.runtimeProvider = "python-langgraph";
            snapshot.metadata = metadataService.mergeMetadata(snapshot.metadata, Map.of(
                    "resumeRequested", true,
                    "resumeMode", resumeMode,
                    "runtimeTarget", "python",
                    "resumedAt", Instant.now().toString()));
            persistenceService.save(snapshot);
            eventBus.publish(workflowId, "WORKFLOW_STARTED", "workflow", "RUNNING",
                    "Resuming Python workflow via gRPC | mode=" + resumeMode);

            HybridRouteDecision decision = snapshot.routeDecision;
            Map<String, Object> metadata = snapshot.metadata;
            AgentWorkflowProto.RunWorkflowRequest.Builder builder =
                    AgentWorkflowProto.RunWorkflowRequest.newBuilder()
                            .setWorkflowId(workflowId)
                            .setEngine(decision == null ? "" : defaultString(decision.engine, ""))
                            .setProfile(decision == null ? "" : defaultString(decision.profile, ""))
                            .setUserId(metadataValue(metadata, "userId", "user-001"))
                            .setResearchGroupId(metadataValue(metadata, "researchGroupId", "replace-me"))
                            .setConversationId(metadataValue(metadata, "conversationId", ""))
                            .setMessage(metadataValue(metadata, "message", ""))
                            .setResume(true)
                            .setResumeMode(resumeMode)
                            .setMetadata(AgentGrpcClient.mapToStruct(Map.of(
                                    "catalogVersion", ExecutionCatalogService.CATALOG_VERSION,
                                    "runtimeTarget", "python",
                                    "resumeMode", resumeMode)));
            if (decision != null) {
                builder.setRouteDecision(toProto(decision));
            }
            grpcClient.runWorkflow(
                    builder.build(),
                    eventBus,
                    terminal -> resultApplicationService.applyGrpc(workflowId, terminal),
                    600);
        } catch (Exception error) {
            log.error("Resume failed | workflowId={}, error={}", workflowId, error.getMessage(), error);
            snapshot.status = "FAILED";
            snapshot.errorMessage = "Resume failed: " + error.getMessage();
            snapshot.completedAt = Instant.now();
            eventBus.publish(workflowId, "WORKFLOW_FAILED", "workflow", "FAILED", error.getMessage());
            persistenceService.save(snapshot);
            snapshotHydrator.hydrate(snapshot);
        }
    }

    private AgentWorkflowProto.RunWorkflowRequest buildRequest(
            String workflowId,
            WorkflowRunRequest request,
            HybridRouteDecision decision,
            MemoryRecallRecord recall,
            ContextEnvelope contextEnvelope) {
        AgentWorkflowProto.RunWorkflowRequest.Builder builder =
                AgentWorkflowProto.RunWorkflowRequest.newBuilder()
                        .setWorkflowId(workflowId)
                        .setEngine(decision == null
                                ? defaultString(request.engine, "")
                                : defaultString(decision.engine, ""))
                        .setProfile(decision == null
                                ? defaultString(request.profile, "")
                                : defaultString(decision.profile, ""))
                        .setUserId(defaultString(request.userId, "user-001"))
                        .setResearchGroupId(defaultString(request.researchGroupId, "replace-me"))
                        .setConversationId(defaultString(request.conversationId, ""))
                        .setMessage(defaultString(request.message, ""));
        if (decision != null) {
            builder.setRouteDecision(toProto(decision));
        }
        if (recall != null) {
            Map<String, Object> recallRef = new LinkedHashMap<>();
            recallRef.put("recallId", recall.recallId);
            recallRef.put("compactPromptContext", recall.compactPromptContext);
            recallRef.put("diagnostics", recall.diagnostics);
            builder.setMemoryRecallRef(AgentGrpcClient.mapToStruct(recallRef));
        }
        if (contextEnvelope != null) {
            builder.setContextBudget(AgentGrpcClient.mapToStruct(
                    objectMapper.convertValue(contextEnvelope, Map.class)));
        }
        builder.setMetadata(AgentGrpcClient.mapToStruct(Map.of(
                "source", metadataService.resolveSource(request),
                "runtimeTarget", metadataService.inferRuntimeTarget(decision),
                "catalogVersion", ExecutionCatalogService.CATALOG_VERSION)));
        return builder.build();
    }

    private AgentWorkflowProto.RouteDecisionMsg toProto(HybridRouteDecision decision) {
        return AgentWorkflowProto.RouteDecisionMsg.newBuilder()
                .setIntent(defaultString(decision.taskType, ""))
                .setEngine(defaultString(decision.engine, ""))
                .setTaskType(defaultString(decision.taskType, ""))
                .setProfile(defaultString(decision.profile, ""))
                .setConfidence(decision.confidence)
                .setReason(defaultString(decision.reason, ""))
                .addAllSignals(decision.signals == null ? List.of() : decision.signals)
                .setExecutionMode(defaultString(decision.executionMode, ""))
                .build();
    }

    private String metadataValue(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? fallback : defaultString(String.valueOf(value), fallback);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
