package com.team.rag.agent.runtime;

import com.team.rag.agent.routing.HybridRouteDecision;
import com.team.rag.entity.RetrievalDiagnosticsResult;
import com.team.rag.service.MemoryExtractionService;
import com.team.rag.service.RetrievalDiagnosticsService;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Shared diagnostics and memory-writeback policy for Java and Python completions. */
@Service
public class WorkflowPostProcessingService {

    private final RetrievalDiagnosticsService diagnosticsService;
    private final MemoryExtractionService memoryExtractionService;
    private final WorkflowEventBus eventBus;

    public WorkflowPostProcessingService(RetrievalDiagnosticsService diagnosticsService,
                                         MemoryExtractionService memoryExtractionService,
                                         WorkflowEventBus eventBus) {
        this.diagnosticsService = diagnosticsService;
        this.memoryExtractionService = memoryExtractionService;
        this.eventBus = eventBus;
    }

    public void captureDiagnosticsIfNeeded(String userId, String knowledgeSpaceId, String message,
                                           WorkflowRunSnapshot snapshot, String answer) {
        boolean suspicious = answer == null
                || answer.contains("No sufficient evidence")
                || answer.contains("超出知识范围")
                || answer.contains("knowledge range")
                || answer.contains("current literature library");
        if (!suspicious || snapshot == null) {
            return;
        }
        RetrievalDiagnosticsResult diagnostics = diagnosticsService.diagnose(
                userId, knowledgeSpaceId, message, 10, 0.0);
        snapshot.retrievalDiagnostics.add(diagnostics);
    }

    public void queueMemoryWriteback(String userId, String knowledgeSpaceId, String conversationId,
                                     String workflowId, String message, String finalAnswer,
                                     HybridRouteDecision routeDecision) {
        eventBus.publish(workflowId, "MEMORY_WRITEBACK_STARTED", "memory_writeback", "MemoryExtractionService", null,
                "RUNNING", "Memory writeback queued", Map.of("conversationId", safe(conversationId)));
        memoryExtractionService.captureAfterWorkflow(
                userId, knowledgeSpaceId, conversationId, workflowId, message, finalAnswer, routeDecision);
        eventBus.publish(workflowId, "MEMORY_WRITEBACK_DONE", "memory_writeback", "MemoryExtractionService", null,
                "SUCCESS", "Memory writeback task submitted", Map.of());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
