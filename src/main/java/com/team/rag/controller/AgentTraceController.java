package com.team.rag.controller;

import com.team.rag.agent.trace.AgentTraceService;
import com.team.rag.agent.trace.AgentTraceStep;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Global trace compatibility API for all Agent workbench pages.
 */
@RestController
@RequestMapping("/api/agent-traces")
public class AgentTraceController {

    private final AgentTraceService traceService;

    public AgentTraceController(AgentTraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<List<AgentTraceStep>> getTraces(@PathVariable String workflowId) {
        return ResponseEntity.ok(traceService.getWorkflowTraces(workflowId));
    }

    @GetMapping("/{workflowId}/stats")
    public ResponseEntity<AgentTraceService.TraceStatistics> getStats(@PathVariable String workflowId) {
        return ResponseEntity.ok(traceService.getWorkflowStatistics(workflowId));
    }
}
