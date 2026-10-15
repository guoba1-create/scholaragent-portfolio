package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.routing.ExecutionDecision;
import com.team.rag.identity.RequestIdentity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

/** Produces user-safe workflow views without mutating the persisted snapshot. */
@Service
public class WorkflowSnapshotViewService {

    private final ObjectMapper objectMapper;

    public WorkflowSnapshotViewService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WorkflowRunSnapshot forIdentity(WorkflowRunSnapshot source, RequestIdentity identity) {
        if (source == null || identity == null || identity.admin()) {
            return source;
        }
        WorkflowRunSnapshot view = objectMapper.convertValue(source, WorkflowRunSnapshot.class);
        view.executionDecision = userDecision(source.executionDecision);
        view.routeDecision = null;
        view.engine = null;
        view.runtimeTarget = null;
        view.runtimeProvider = null;
        view.runtimeMetadata = null;
        view.metadata = null;
        view.memoryRecall = null;
        view.contextBudget = null;
        view.contextEnvelope = null;
        view.contextStats = null;
        view.toolObservations = new ArrayList<>();
        view.retrievalDiagnostics = new ArrayList<>();
        view.events = new ArrayList<>();
        view.traces = new ArrayList<>();
        view.stats = null;
        view.reactSteps = new ArrayList<>();
        view.messages = new ArrayList<>();
        view.controlHistory = new ArrayList<>();
        return view;
    }

    public WorkflowEvent eventForIdentity(WorkflowEvent source, RequestIdentity identity) {
        if (source == null || identity == null || identity.admin()) {
            return source;
        }
        WorkflowEvent view = new WorkflowEvent();
        view.setEventId(source.getEventId());
        view.setWorkflowId(source.getWorkflowId());
        view.setEventType(source.getEventType());
        view.setPhase(userPhase(source.getPhase()));
        view.setStatus(source.getStatus());
        view.setSummary(userSummary(source));
        view.setCreatedAt(source.getCreatedAt());
        view.setPayload(null);
        return view;
    }

    private ExecutionDecision userDecision(ExecutionDecision source) {
        if (source == null) {
            return null;
        }
        ExecutionDecision view = new ExecutionDecision();
        view.intent = source.intent;
        view.strategy = source.strategy;
        view.confidence = source.confidence;
        view.requiresClarification = source.requiresClarification;
        view.safetyDecision = source.safetyDecision;
        view.catalogVersion = source.catalogVersion;
        view.reason = null;
        view.signals = new ArrayList<>();
        view.source = null;
        view.workflowTemplate = null;
        view.agentProfile = null;
        view.engine = null;
        view.taskType = null;
        view.profile = null;
        view.executionMode = null;
        view.metadata = null;
        return view;
    }

    private String userPhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "processing";
        }
        String value = phase.toLowerCase();
        if (value.contains("route")) return "understanding";
        if (value.contains("retriev") || value.contains("evidence")) return "searching";
        if (value.contains("answer") || value.contains("finish") || value.contains("synth")) return "answering";
        return "processing";
    }

    private String userSummary(WorkflowEvent event) {
        String type = event.getEventType() == null ? "" : event.getEventType().toUpperCase();
        if (type.contains("FAILED")) return "The request could not be completed.";
        if (type.contains("DONE") || type.contains("COMPLETED")) return "Processing completed.";
        if (type.contains("ROUTER")) return "The request path was selected.";
        return "Processing the request.";
    }
}
