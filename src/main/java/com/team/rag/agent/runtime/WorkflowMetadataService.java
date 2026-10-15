package com.team.rag.agent.runtime;

import com.team.rag.agent.routing.HybridRouteDecision;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds and merges workflow metadata for snapshot/runtime handoff.
 */
@Service
public class WorkflowMetadataService {

    public Map<String, Object> createInitialMetadata(String workflowId,
                                                     WorkflowRunRequest request,
                                                     HybridRouteDecision routeDecision,
                                                     String runtimeTarget) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", defaultString(request.userId, "user-001"));
        metadata.put("researchGroupId", defaultString(request.researchGroupId, "replace-me"));
        metadata.put("conversationId", defaultString(request.conversationId,
                buildDefaultConversationId(defaultString(request.userId, "user-001"))));
        metadata.put("source", resolveSource(request));
        metadata.put("requestedEngine", defaultString(request.engine, "auto"));
        metadata.put("requestedProfile", defaultString(request.profile, "auto"));
        metadata.put("runtimeTarget", defaultString(runtimeTarget, "java"));
        metadata.put("legacyMode", defaultString(request.mode, "auto"));
        metadata.put("profile", defaultString(request.profile, "auto"));
        metadata.put("message", defaultString(request.message, ""));
        metadata.put("effectiveEngine", routeDecision == null ? "" : routeDecision.engine);
        metadata.put("taskType", routeDecision == null ? "" : routeDecision.taskType);
        metadata.put("stateRefs", stateRefs(workflowId));
        return metadata;
    }

    public Map<String, Object> mergeMetadata(Map<String, Object> current, Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        if (updates != null) {
            merged.putAll(updates);
        }
        return merged;
    }

    public String buildDefaultConversationId(String userId) {
        return defaultString(userId, "user-001") + ":default";
    }

    public String resolveSource(WorkflowRunRequest request) {
        String source = defaultString(request.source, "");
        if (source.isBlank() || "auto".equalsIgnoreCase(source)) {
            source = defaultString(request.mode, "");
        }
        if (source.isBlank() || "auto".equalsIgnoreCase(source)) {
            return "web";
        }
        return source.trim().toLowerCase(Locale.ROOT);
    }

    public String inferRuntimeTarget(HybridRouteDecision routeDecision) {
        if (routeDecision == null) {
            return "java";
        }
        if ("rag".equalsIgnoreCase(routeDecision.engine) || "reject".equalsIgnoreCase(routeDecision.taskType)) {
            return "java";
        }
        return "python";
    }

    private Map<String, Object> stateRefs(String workflowId) {
        return Map.of(
                "snapshotKey", "workflow:snapshot:" + workflowId,
                "eventKey", "workflow:events:" + workflowId,
                "reactStepKey", "workflow:react-steps:" + workflowId,
                "toolObservationKey", "workflow:tool-observations:" + workflowId,
                "messageKey", "workflow:agent-messages:" + workflowId
        );
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
