package com.team.rag.agent.routing;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Product-level routing contract. Legacy engine/profile fields remain during
 * the API migration, while intent and strategy are the authoritative concepts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecutionDecision {
    public ExecutionIntent intent;
    public ExecutionStrategy strategy;
    public WorkflowTemplate workflowTemplate;
    public String agentProfile;
    public double confidence;
    public String reason;
    public List<String> signals = new ArrayList<>();
    public DecisionSource source;
    public boolean requiresClarification;
    public SafetyDecision safetyDecision = SafetyDecision.ALLOW;
    public String catalogVersion;

    /** Backward-compatible aliases. */
    public String engine;
    public String taskType;
    public String profile;
    public String executionMode;
    public Map<String, Object> metadata;
    public Instant createdAt = Instant.now();

    public HybridRouteDecision toLegacy() {
        HybridRouteDecision legacy = HybridRouteDecision.of(
                engine, taskType, profile, confidence, reason);
        legacy.intent = taskType;
        legacy.executionMode = executionMode;
        legacy.signals = new ArrayList<>(signals == null ? List.of() : signals);
        legacy.metadata = metadata;
        return legacy;
    }
}
