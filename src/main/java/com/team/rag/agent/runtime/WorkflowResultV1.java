package com.team.rag.agent.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Data transfer object for Python runtime results.
 * Used internally to project the terminal gRPC event into snapshot state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowResultV1 {

    public String schemaVersion = "v1";
    public String workflowId;
    public String status;
    public String finalAnswer;
    public String errorMessage;
    public List<Map<String, Object>> reactSteps;
    public List<Map<String, Object>> traces;
    public List<Map<String, Object>> messages;
    public List<Map<String, Object>> toolObservations;
    public List<Map<String, Object>> documentRefs;
    public List<Map<String, Object>> retrievalDiagnostics;
    public Object claimEvidenceTable;
    public Object qualityReport;
    public List<String> warnings;
    public Map<String, Object> stats;
    public Map<String, Object> metadata;
}
