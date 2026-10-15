package com.team.rag.agent.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.rag.agent.model.AgentMessage;
import com.team.rag.agent.model.ReActStep;
import com.team.rag.agent.context.ContextEnvelope;
import com.team.rag.agent.context.ContextStats;
import com.team.rag.agent.routing.ExecutionDecision;
import com.team.rag.agent.routing.HybridRouteDecision;
import com.team.rag.agent.retrieval.RetrievalContext;
import com.team.rag.agent.trace.AgentTraceService;
import com.team.rag.agent.trace.AgentTraceStep;
import com.team.rag.entity.DocumentChunkRef;
import com.team.rag.entity.RetrievalDiagnosticsResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aggregated workflow view consumed by the workbench.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowRunSnapshot {

    public String workflowId;
    public String engine;
    public String runtimeTarget = "java";
    public String runtimeProvider = "java";
    public Map<String, Object> runtimeMetadata;
    public String status;
    public Instant createdAt;
    public Instant completedAt;
    public String finalAnswer;
    public String errorMessage;
    public HybridRouteDecision routeDecision;
    /** Product-level authoritative routing decision. */
    public ExecutionDecision executionDecision;

    public List<String> warnings = new ArrayList<>();
    public Object qualityReport;
    public Object claimEvidenceTable;

    public MemoryRecallRecord memoryRecall;
    public ContextBudgetSnapshot contextBudget;
    /** Versioned context package shared by Java and Python runtimes. */
    public ContextEnvelope contextEnvelope;
    public ContextStats contextStats;
    /** One retrieval result reused by generation, citations, diagnostics and UI. */
    public RetrievalContext retrievalContext;

    public List<ToolObservation> toolObservations = new ArrayList<>();
    public List<RetrievalDiagnosticsResult> retrievalDiagnostics = new ArrayList<>();
    public List<DocumentChunkRef> documentRefs = new ArrayList<>();

    public List<WorkflowEvent> events = new ArrayList<>();
    public List<AgentTraceStep> traces = new ArrayList<>();
    public AgentTraceService.TraceStatistics stats;
    public List<ReActStep> reactSteps = new ArrayList<>();
    public List<AgentMessage> messages = new ArrayList<>();

    /** Control-plane command history for interrupt/resume tracking and auditing. */
    public List<Map<String, Object>> controlHistory = new ArrayList<>();

    /** Free-form metadata for UI diagnostics and lifecycle bookkeeping. */
    public Map<String, Object> metadata;
}
