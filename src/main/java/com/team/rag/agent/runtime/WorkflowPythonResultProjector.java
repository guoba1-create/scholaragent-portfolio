package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.model.AgentMessage;
import com.team.rag.agent.model.ReActStep;
import com.team.rag.agent.trace.AgentTraceService;
import com.team.rag.entity.DocumentChunkRef;
import com.team.rag.entity.RetrievalDiagnosticsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Python runtime payloads into Java-side stores and snapshot-ready structures.
 */
@Service
public class WorkflowPythonResultProjector {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPythonResultProjector.class);

    private final ObjectMapper objectMapper;
    private final ReActStepStore reActStepStore;
    private final AgentTraceService traceService;
    private final AgentMessageBus messageBus;
    private final ToolObservationStore toolObservationStore;
    private final ToolResultCompressor toolResultCompressor;

    public WorkflowPythonResultProjector(ObjectMapper objectMapper,
                                         ReActStepStore reActStepStore,
                                         AgentTraceService traceService,
                                         AgentMessageBus messageBus,
                                         ToolObservationStore toolObservationStore,
                                         ToolResultCompressor toolResultCompressor) {
        this.objectMapper = objectMapper;
        this.reActStepStore = reActStepStore;
        this.traceService = traceService;
        this.messageBus = messageBus;
        this.toolObservationStore = toolObservationStore;
        this.toolResultCompressor = toolResultCompressor;
    }

    public Projection project(String workflowId, WorkflowResultV1 result) {
        Projection projection = new Projection();
        if (result == null) {
            return projection;
        }

        if (result.reactSteps != null) {
            List<ReActStep> convertedSteps = new ArrayList<>();
            for (Map<String, Object> stepMap : result.reactSteps) {
                try {
                    ReActStep step = objectMapper.convertValue(stepMap, ReActStep.class);
                    step.setWorkflowId(workflowId);
                    convertedSteps.add(step);
                } catch (Exception e) {
                    log.warn("Failed to convert reactStep | workflowId={}, error={}", workflowId, e.getMessage());
                }
            }
            reActStepStore.setSteps(workflowId, convertedSteps);
        }

        if (result.traces != null) {
            List<com.team.rag.agent.trace.AgentTraceStep> convertedTraces = new ArrayList<>();
            for (Map<String, Object> traceMap : result.traces) {
                try {
                    com.team.rag.agent.trace.AgentTraceStep trace =
                            objectMapper.convertValue(traceMap, com.team.rag.agent.trace.AgentTraceStep.class);
                    trace.setWorkflowId(workflowId);
                    convertedTraces.add(trace);
                } catch (Exception e) {
                    log.warn("Failed to convert trace | workflowId={}, error={}", workflowId, e.getMessage());
                }
            }
            traceService.setWorkflowTraces(workflowId, convertedTraces);
            projection.tracePayloadApplied = !convertedTraces.isEmpty();
        }

        if (result.messages != null) {
            List<AgentMessage> convertedMessages = new ArrayList<>();
            for (Map<String, Object> msgMap : result.messages) {
                try {
                    AgentMessage msg = objectMapper.convertValue(msgMap, AgentMessage.class);
                    msg.setWorkflowId(workflowId);
                    convertedMessages.add(msg);
                } catch (Exception e) {
                    log.warn("Failed to convert message | workflowId={}, error={}", workflowId, e.getMessage());
                }
            }
            messageBus.setWorkflowMessages(workflowId, convertedMessages);
        }

        if (result.toolObservations != null) {
            List<ToolObservation> observations = new ArrayList<>();
            for (Map<String, Object> observationMap : result.toolObservations) {
                try {
                    ToolObservation observation = objectMapper.convertValue(observationMap, ToolObservation.class);
                    observation.workflowId = workflowId;
                    observations.add(toolResultCompressor.normalizeExisting(workflowId, observation));
                } catch (Exception e) {
                    log.warn("Failed to convert tool observation | workflowId={}, error={}", workflowId, e.getMessage());
                }
            }
            toolObservationStore.setObservations(workflowId, observations);
        }

        if (result.documentRefs != null) {
            List<DocumentChunkRef> refs = new ArrayList<>();
            for (Map<String, Object> refMap : result.documentRefs) {
                try {
                    refs.add(objectMapper.convertValue(refMap, DocumentChunkRef.class));
                } catch (Exception e) {
                    log.warn("Failed to convert document ref | workflowId={}, error={}", workflowId, e.getMessage());
                }
            }
            projection.documentRefs = refs;
        }

        if (result.retrievalDiagnostics != null) {
            List<RetrievalDiagnosticsResult> diagnostics = new ArrayList<>();
            for (Map<String, Object> diagnosticsMap : result.retrievalDiagnostics) {
                try {
                    diagnostics.add(objectMapper.convertValue(diagnosticsMap, RetrievalDiagnosticsResult.class));
                } catch (Exception e) {
                    log.warn("Failed to convert retrieval diagnostics | workflowId={}, error={}", workflowId, e.getMessage());
                }
            }
            projection.retrievalDiagnostics = diagnostics;
        }

        projection.claimEvidenceTable = result.claimEvidenceTable;
        projection.qualityReport = result.qualityReport;
        projection.warnings = result.warnings == null ? null : new ArrayList<>(result.warnings);
        projection.runtimeMetadata = result.metadata == null ? null : new LinkedHashMap<>(result.metadata);
        return projection;
    }

    public static class Projection {
        public boolean tracePayloadApplied;
        public List<DocumentChunkRef> documentRefs;
        public List<RetrievalDiagnosticsResult> retrievalDiagnostics;
        public Object claimEvidenceTable;
        public Object qualityReport;
        public List<String> warnings;
        public Map<String, Object> runtimeMetadata;
    }
}
