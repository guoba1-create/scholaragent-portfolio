package com.team.rag.agent.routing;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.team.rag.monitoring.AgentBusinessMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authoritative routing facade. HybridRouterService remains the low-level
 * rule/LLM classifier; all controllers and workflow runs consume this service.
 */
@Service
public class ExecutionDecisionService {

    private final HybridRouterService hybridRouter;
    private final ExecutionCatalogService catalog;
    private final AgentBusinessMetrics metrics;

    public ExecutionDecisionService(HybridRouterService hybridRouter, ExecutionCatalogService catalog) {
        this(hybridRouter, catalog, null);
    }

    @Autowired
    public ExecutionDecisionService(HybridRouterService hybridRouter, ExecutionCatalogService catalog,
                                    AgentBusinessMetrics metrics) {
        this.hybridRouter = hybridRouter;
        this.catalog = catalog;
        this.metrics = metrics;
    }

    public ExecutionDecision decide(String message, String requestedMode, String requestedProfile) {
        String mode = normalizeMode(requestedMode);
        HybridRouteDecision legacy = hybridRouter.decide(message, legacyEngine(mode), "auto");

        boolean empty = message == null || message.isBlank();
        boolean rejected = "reject".equals(legacy.taskType);
        if (empty) {
            legacy.engine = "rag";
            legacy.taskType = "clarification_needed";
            legacy.intent = legacy.taskType;
            legacy.profile = "auto";
            legacy.confidence = 1.0;
            legacy.reason = "The input is empty and requires clarification.";
            legacy.signals = new ArrayList<>(List.of("empty_input", "clarification_required"));
        } else if (!rejected && "deep".equals(mode)) {
            legacy.engine = "react";
            legacy.taskType = "deep_research";
            legacy.intent = legacy.taskType;
            legacy.profile = ExecutionCatalogService.DEEP_RESEARCH_PROFILE;
            legacy.executionMode = null;
            legacy.confidence = 0.99;
            legacy.reason = "Manual deep-research mode selected.";
            legacy.addSignal("manual_mode:deep");
        } else if (!rejected && "quick".equals(mode)) {
            legacy.engine = "rag";
            if (!"chitchat".equals(legacy.taskType) && !"paper_qa".equals(legacy.taskType)) {
                legacy.taskType = "paper_qa";
                legacy.intent = legacy.taskType;
            }
            legacy.profile = "auto";
            legacy.executionMode = null;
            legacy.confidence = Math.max(legacy.confidence, 0.99);
            legacy.reason = "Manual quick-answer mode selected. " + safe(legacy.reason);
            legacy.addSignal("manual_mode:quick");
        } else if (!rejected && "evidence".equals(mode)) {
            legacy.engine = "workflow";
            if (catalog.templateForTask(legacy.taskType) == null) {
                legacy.taskType = "claim_verification";
                legacy.intent = legacy.taskType;
            }
            legacy.profile = "auto";
            legacy.executionMode = null;
            legacy.confidence = Math.max(legacy.confidence, 0.99);
            legacy.reason = "Manual evidence-analysis mode selected. " + safe(legacy.reason);
            legacy.addSignal("manual_mode:evidence");
        }

        if (!rejected && requestedProfile != null && !requestedProfile.isBlank()
                && !"auto".equalsIgnoreCase(requestedProfile)) {
            if (!catalog.isAgentProfile(requestedProfile)) {
                throw new IllegalArgumentException("Unknown or deprecated agent profile: " + requestedProfile);
            }
            legacy.profile = requestedProfile;
            legacy.engine = "react";
            legacy.taskType = ExecutionCatalogService.EXTERNAL_SCOUT_PROFILE.equals(requestedProfile)
                    ? "external_scout" : "deep_research";
            legacy.intent = legacy.taskType;
            legacy.addSignal("manual_agent_profile:" + requestedProfile);
        }
        ExecutionDecision decision = fromLegacy(legacy, mode);
        if (metrics != null) {
            metrics.decision(decision);
        }
        return decision;
    }

    public ExecutionDecision fromLegacy(HybridRouteDecision legacy, String requestedMode) {
        ExecutionDecision decision = new ExecutionDecision();
        String task = legacy == null ? "clarification_needed" : safe(legacy.taskType);
        decision.intent = intent(task);
        decision.strategy = strategy(legacy, decision.intent);
        decision.workflowTemplate = catalog.templateForTask(task);
        decision.agentProfile = decision.strategy == ExecutionStrategy.DEEP_RESEARCH
                ? normalizeAgentProfile(legacy == null ? null : legacy.profile)
                : null;
        decision.confidence = legacy == null ? 0.0 : clamp(legacy.confidence);
        decision.reason = legacy == null ? "No route decision was produced." : legacy.reason;
        decision.signals = legacy == null || legacy.signals == null
                ? new ArrayList<>() : new ArrayList<>(legacy.signals);
        decision.source = source(decision.signals, requestedMode, decision.reason);
        decision.requiresClarification = decision.intent == ExecutionIntent.CLARIFY;
        decision.safetyDecision = decision.intent == ExecutionIntent.REJECT
                ? SafetyDecision.REJECT : SafetyDecision.ALLOW;
        decision.catalogVersion = ExecutionCatalogService.CATALOG_VERSION;

        decision.engine = legacy == null ? "rag" : legacy.engine;
        decision.taskType = task;
        decision.profile = legacy == null ? "auto" : legacy.profile;
        // Kept as a nullable compatibility alias. Fixed workflow templates no
        // longer select a second topology through executionMode.
        decision.executionMode = null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (legacy != null && legacy.metadata != null) {
            metadata.putAll(legacy.metadata);
        }
        metadata.put("catalogVersion", ExecutionCatalogService.CATALOG_VERSION);
        metadata.put("requestedMode", requestedMode == null ? "auto" : requestedMode);
        decision.metadata = metadata;
        return decision;
    }

    private ExecutionIntent intent(String task) {
        return switch (task) {
            case "chitchat", "general_chat" -> ExecutionIntent.CHITCHAT;
            case "claim_verification", "paper_compare", "related_work", "citation_recommendation" ->
                    ExecutionIntent.EVIDENCE_ANALYSIS;
            case "deep_research", "research_analysis", "research_gap", "external_scout" ->
                    ExecutionIntent.DEEP_RESEARCH;
            case "reject" -> ExecutionIntent.REJECT;
            case "clarification_needed" -> ExecutionIntent.CLARIFY;
            default -> ExecutionIntent.PAPER_QA;
        };
    }

    private ExecutionStrategy strategy(HybridRouteDecision legacy, ExecutionIntent intent) {
        if (intent == ExecutionIntent.CHITCHAT || intent == ExecutionIntent.REJECT
                || intent == ExecutionIntent.CLARIFY) {
            return ExecutionStrategy.QUICK_QA;
        }
        if (intent == ExecutionIntent.DEEP_RESEARCH) {
            return ExecutionStrategy.DEEP_RESEARCH;
        }
        if (intent == ExecutionIntent.EVIDENCE_ANALYSIS) {
            return ExecutionStrategy.EVIDENCE_WORKFLOW;
        }
        return ExecutionStrategy.QUICK_QA;
    }

    private DecisionSource source(List<String> signals, String mode, String reason) {
        if (!"auto".equals(normalizeMode(mode)) || signals.stream().anyMatch(item -> item.startsWith("manual_"))) {
            return DecisionSource.MANUAL;
        }
        if (reason != null && reason.startsWith("LLM:")) {
            return DecisionSource.LLM;
        }
        if (signals.stream().anyMatch(item -> item.contains("fallback") || item.contains("default"))) {
            return DecisionSource.FALLBACK;
        }
        return DecisionSource.RULE;
    }

    private String normalizeMode(String value) {
        if (value == null || value.isBlank()) {
            return "auto";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "rag", "quick", "chat" -> "quick";
            case "react", "deep", "deep_research" -> "deep";
            case "workflow", "evidence" -> "evidence";
            default -> "auto";
        };
    }

    private String legacyEngine(String mode) {
        return switch (mode) {
            case "quick" -> "rag";
            case "deep" -> "react";
            case "evidence" -> "workflow";
            default -> "auto";
        };
    }

    private String normalizeAgentProfile(String profile) {
        return catalog.isAgentProfile(profile)
                ? profile : ExecutionCatalogService.DEEP_RESEARCH_PROFILE;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
