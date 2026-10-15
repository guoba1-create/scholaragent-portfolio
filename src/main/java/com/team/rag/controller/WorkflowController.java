package com.team.rag.controller;

import com.team.rag.agent.grpc.AgentGrpcClient;
import com.team.rag.agent.grpc.AgentWorkflowProto;
import com.team.rag.agent.runtime.WorkflowEvent;
import com.team.rag.agent.runtime.WorkflowEventBus;
import com.team.rag.agent.runtime.WorkflowRunRequest;
import com.team.rag.agent.runtime.WorkflowRunService;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import com.team.rag.agent.runtime.WorkflowSnapshotViewService;
import com.team.rag.identity.RequestIdentity;
import com.team.rag.identity.RequestIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified workflow API controller for the Agent Workbench.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private static final Duration SSE_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final WorkflowRunService workflowRunService;
    private final WorkflowEventBus eventBus;
    private final AgentGrpcClient grpcClient;
    private final RequestIdentityResolver identityResolver;
    private final WorkflowSnapshotViewService snapshotViewService;

    public WorkflowController(WorkflowRunService workflowRunService,
                              WorkflowEventBus eventBus,
                              AgentGrpcClient grpcClient,
                              RequestIdentityResolver identityResolver,
                              WorkflowSnapshotViewService snapshotViewService) {
        this.workflowRunService = workflowRunService;
        this.eventBus = eventBus;
        this.grpcClient = grpcClient;
        this.identityResolver = identityResolver;
        this.snapshotViewService = snapshotViewService;
    }

    @PostMapping("/run")
    public ResponseEntity<WorkflowRunSnapshot> run(@RequestBody(required = false) WorkflowRunRequest request,
                                                   HttpServletRequest servletRequest) {
        return ResponseEntity.ok(workflowRunService.startRun(identityResolver.bind(request, servletRequest)));
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<WorkflowRunSnapshot> getSnapshot(@PathVariable String workflowId,
                                                           HttpServletRequest servletRequest) {
        WorkflowRunSnapshot snapshot = workflowRunService.getSnapshot(workflowId);
        identityResolver.assertWorkflowAccess(snapshot, servletRequest);
        RequestIdentity identity = identityResolver.resolve(servletRequest);
        return ResponseEntity.ok(snapshotViewService.forIdentity(snapshot, identity));
    }

    @GetMapping("/{workflowId}/context-summary")
    public ResponseEntity<Map<String, Object>> getContextSummary(@PathVariable String workflowId,
                                                                 HttpServletRequest servletRequest) {
        WorkflowRunSnapshot snapshot = workflowRunService.getSnapshot(workflowId);
        identityResolver.assertWorkflowAccess(snapshot, servletRequest);
        if (snapshot.contextEnvelope == null) {
            return ResponseEntity.ok(Map.of(
                    "workflowId", workflowId,
                    "available", false));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workflowId", workflowId);
        response.put("available", true);
        response.put("contextId", snapshot.contextEnvelope.contextId);
        response.put("strategy", snapshot.contextEnvelope.strategy);
        response.put("fingerprint", snapshot.contextEnvelope.fingerprint);
        response.put("stats", snapshot.contextStats);
        response.put("budget", snapshot.contextEnvelope.budget);
        response.put("compressionCount", snapshot.contextEnvelope.compressionRecords.size());
        response.put("evidenceCount", snapshot.contextEnvelope.evidenceRefs.size());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{workflowId}/control")
    public ResponseEntity<Map<String, Object>> controlWorkflow(@PathVariable String workflowId,
                                                               @RequestBody WorkflowControlRequest request,
                                                               HttpServletRequest servletRequest) {
        WorkflowControlRequest safeRequest = request == null ? new WorkflowControlRequest() : request;
        WorkflowRunSnapshot snapshot = workflowRunService.getSnapshot(workflowId);
        identityResolver.assertWorkflowAccess(snapshot, servletRequest);
        if (snapshot == null || "UNKNOWN".equalsIgnoreCase(snapshot.status)) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "workflowId", workflowId,
                    "error", "Workflow not found"
            ));
        }

        String command = normalizeCommand(safeRequest.command);
        if (command == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "workflowId", workflowId,
                    "error", "Unsupported command. Use INTERRUPT or RESUME."
            ));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (safeRequest.payload != null) {
            payload.putAll(safeRequest.payload);
        }
        if (safeRequest.message != null && !safeRequest.message.isBlank()) {
            payload.put("message", safeRequest.message);
        }

        payload.putIfAbsent("engine", snapshot.engine);
        payload.putIfAbsent("workflowStatus", snapshot.status);

        if (snapshot.metadata != null) {
            payload.putIfAbsent("userId", snapshot.metadata.get("userId"));
            payload.putIfAbsent("researchGroupId", snapshot.metadata.get("researchGroupId"));
            payload.putIfAbsent("conversationId", snapshot.metadata.get("conversationId"));
            payload.putIfAbsent("profile", snapshot.metadata.get("profile"));
        }
        if (snapshot.routeDecision != null) {
            payload.putIfAbsent("routeDecision", snapshot.routeDecision);
            payload.putIfAbsent("taskType", snapshot.routeDecision.taskType);
            payload.putIfAbsent("profile", snapshot.routeDecision.profile);
        }
        if (snapshot.runtimeMetadata != null) {
            payload.putIfAbsent("runtimeMetadata", snapshot.runtimeMetadata);
            payload.putIfAbsent("taskType", snapshot.runtimeMetadata.get("taskType"));
            payload.putIfAbsent("profile", snapshot.runtimeMetadata.get("profile"));
        }

        if ("RESUME".equals(command)) {
            return handleResume(workflowId, snapshot, payload);
        }

        // INTERRUPT via gRPC
        try {
            AgentWorkflowProto.ControlRequest grpcRequest = AgentWorkflowProto.ControlRequest.newBuilder()
                    .setWorkflowId(workflowId)
                    .setCommand(command)
                    .setPayload(AgentGrpcClient.mapToStruct(payload))
                    .build();

            AgentWorkflowProto.ControlResponse grpcResponse = grpcClient.control(grpcRequest);

            workflowRunService.recordControlCommand(
                    workflowId, command,
                    grpcResponse.getSuccess() ? "REQUESTED" : "FAILED",
                    payload,
                    grpcResponse.getSuccess() ? "Control command sent via gRPC" : grpcResponse.getError()
            );

            eventBus.publish(workflowId, "CONTROL_REQUESTED", "control",
                    "WorkflowController", null, "RUNNING",
                    command + " requested from control API",
                    Map.of("command", command, "payload", payload));

            return ResponseEntity.ok(Map.of(
                    "success", grpcResponse.getSuccess(),
                    "workflowId", workflowId,
                    "command", command,
                    "payload", payload
            ));
        } catch (Exception e) {
            log.error("gRPC control call failed | workflowId={}, command={}, error={}",
                    workflowId, command, e.getMessage());
            workflowRunService.recordControlCommand(
                    workflowId, command, "FAILED", payload, "gRPC call failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "workflowId", workflowId,
                    "command", command,
                    "error", "gRPC call failed: " + e.getMessage()
            ));
        }
    }

    private ResponseEntity<Map<String, Object>> handleResume(String workflowId,
                                                              WorkflowRunSnapshot snapshot,
                                                              Map<String, Object> payload) {
        String resumeMode = snapshot.status != null && "INTERRUPTED".equalsIgnoreCase(snapshot.status)
                ? "checkpoint" : "restart";

        workflowRunService.recordControlCommand(
                workflowId, "RESUME", "REQUESTED", payload,
                "Resume requested via gRPC | mode=" + resumeMode);

        eventBus.publish(workflowId, "CONTROL_REQUESTED", "control",
                "WorkflowController", null, "RUNNING",
                "RESUME requested from control API",
                Map.of("command", "RESUME", "resumeMode", resumeMode));

        workflowRunService.resumeRun(workflowId, resumeMode);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "workflowId", workflowId,
                "command", "RESUME",
                "resumeMode", resumeMode,
                "payload", payload
        ));
    }

    @GetMapping(value = "/{workflowId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<WorkflowEvent> streamEvents(@PathVariable String workflowId,
                                            HttpServletRequest servletRequest) {
        identityResolver.assertWorkflowAccess(workflowRunService.getSnapshot(workflowId), servletRequest);
        RequestIdentity identity = identityResolver.resolve(servletRequest);
        log.debug("SSE stream opened | workflowId={}", workflowId);

        Flux<WorkflowEvent> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(i -> {
                    WorkflowEvent ping = new WorkflowEvent();
                    ping.setEventType("HEARTBEAT");
                    ping.setPhase("system");
                    ping.setStatus("running");
                    ping.setSummary("keep-alive");
                    return ping;
                });

        Flux<WorkflowEvent> content = eventBus.stream(workflowId)
                .timeout(SSE_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("SSE stream error | workflowId={}, error={}", workflowId, e.getMessage());
                    WorkflowEvent errorEvent = new WorkflowEvent();
                    errorEvent.setEventType("STREAM_ERROR");
                    errorEvent.setPhase("system");
                    errorEvent.setStatus("failed");
                    errorEvent.setSummary("Stream error: " + e.getMessage());
                    return Flux.just(errorEvent);
                });

        return Flux.merge(heartbeat, content)
                .map(event -> snapshotViewService.eventForIdentity(event, identity))
                .doFinally(signal -> log.debug("SSE stream closed | workflowId={}, signal={}", workflowId, signal));
    }

    private String normalizeCommand(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "INTERRUPT", "RESUME" -> normalized;
            default -> null;
        };
    }

    public static class WorkflowControlRequest {
        public String command;
        public String message;
        public Map<String, Object> payload;
    }
}
