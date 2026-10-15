package com.team.rag.controller;

import com.team.rag.service.AgentRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public architecture declaration endpoint for "single mainline" story.
 */
@RestController
@RequestMapping("/api/architecture")
public class ArchitectureController {

    private final AgentRegistryService agentRegistryService;

    public ArchitectureController(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    @GetMapping("/mainline")
    public ResponseEntity<Map<String, Object>> mainline() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Agent Pipeline Single Mainline");
        payload.put("version", "2026-05");
        payload.put("singleMainline", true);
        payload.put("entrypoints", Map.of(
                "run", "POST /api/workflows/run",
                "routeAnalyze", "POST /api/router/analyze",
                "snapshot", "GET /api/workflows/{workflowId}",
                "events", "GET /api/workflows/{workflowId}/events/stream"
        ));
        payload.put("pipeline", List.of(
                stage(1, "route", "Hybrid Router", "intent -> engine/profile -> route decision"),
                stage(2, "memory_recall", "Memory Recall", "short-term + semantic + project context recall"),
                stage(3, "tool_execution", "Tool Execution", "RAG / Workflow / ReAct run with real tool calls"),
                stage(4, "observation", "Tool Compression", "tool observation compression + citations"),
                stage(5, "answer", "Final Answer", "grounded answer / warning / unsupported guidance"),
                stage(6, "memory_writeback", "Memory Writeback", "persist reusable memory after completion"),
                stage(7, "trace_event_snapshot", "Observability", "trace + event + snapshot for replay and diagnostics")
        ));
        payload.put("enginePolicy", Map.of(
                "auto", "runs hybrid route selection",
                "manualOverride", "engine=rag/workflow/react skips engine auto-selection but keeps shared pipeline",
                "runtimeTarget", "rag->java, workflow/react->python-langgraph"
        ));
        payload.put("agentProfiles", agentRegistryService.listAgents());
        payload.put("legacy", Map.of(
                "academicApi", "removed",
                "note", "Use /api/documents/* + /api/workflows/* + /api/router/analyze"
        ));
        return ResponseEntity.ok(payload);
    }

    private static Map<String, Object> stage(int order, String key, String name, String output) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order", order);
        row.put("key", key);
        row.put("name", name);
        row.put("output", output);
        return row;
    }
}
