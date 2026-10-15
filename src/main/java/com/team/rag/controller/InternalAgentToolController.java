package com.team.rag.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.rag.agent.tool.MCPToolRegistry;
import com.team.rag.agent.tool.ToolContext;
import com.team.rag.agent.tool.ToolResult;
import com.team.rag.agent.runtime.RawToolPayloadStore;
import com.team.rag.service.MemoryExtractionService;
import com.team.rag.service.MemoryRecallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Internal tool APIs for the Python LangGraph runtime.
 * These endpoints are called by the Python service during ReAct execution.
 */
@RestController
@RequestMapping("/internal/agent-tools")
public class InternalAgentToolController {

    private static final Logger log = LoggerFactory.getLogger(InternalAgentToolController.class);

    private final MCPToolRegistry toolRegistry;
    private final MemoryRecallService memoryRecallService;
    private final MemoryExtractionService memoryExtractionService;
    private final RawToolPayloadStore rawPayloadStore;

    public InternalAgentToolController(MCPToolRegistry toolRegistry,
                                       MemoryRecallService memoryRecallService,
                                       MemoryExtractionService memoryExtractionService,
                                       RawToolPayloadStore rawPayloadStore) {
        this.toolRegistry = toolRegistry;
        this.memoryRecallService = memoryRecallService;
        this.memoryExtractionService = memoryExtractionService;
        this.rawPayloadStore = rawPayloadStore;
    }

    // --- Request/Response envelopes ---

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallRequest {
        public String workflowId;
        public String userId;
        public String researchGroupId;
        public String conversationId;
        public String agentName;
        public String stepId;
        public String parentStepId;
        public Map<String, Object> routeDecision;
        public Map<String, Object> contextBudget;
        public Map<String, Object> input;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallResponse {
        public boolean success;
        public Object data;
        public String errorCode;
        public String errorMessage;
        public long latencyMs;
        public String rawPayloadRef;

        public static ToolCallResponse fromToolResult(ToolResult<?> result) {
            ToolCallResponse resp = new ToolCallResponse();
            resp.success = result.isSuccess();
            resp.data = result.getData();
            resp.errorCode = result.getErrorCode();
            resp.errorMessage = result.getErrorMessage();
            resp.latencyMs = result.getLatencyMs();
            return resp;
        }
    }

    // --- Health check ---

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "tools", toolRegistry.getAvailableToolNames(),
                "timestamp", Instant.now().toString());
    }

    // --- Tool endpoints ---

    @PostMapping("/list-papers")
    public ToolCallResponse listPapers(@RequestBody ToolCallRequest request) {
        return executeTool("list_papers", request);
    }

    @PostMapping("/get-library-summary")
    public ToolCallResponse getLibrarySummary(@RequestBody ToolCallRequest request) {
        return executeTool("get_library_summary", request);
    }

    @PostMapping("/retrieve-evidence")
    public ToolCallResponse retrieveEvidence(@RequestBody ToolCallRequest request) {
        return executeTool("retrieve_evidence", request);
    }

    @PostMapping("/read-document")
    public ToolCallResponse readDocument(@RequestBody ToolCallRequest request) {
        return executeTool("read_document", request);
    }

    @PostMapping("/query-rewrite")
    public ToolCallResponse queryRewrite(@RequestBody ToolCallRequest request) {
        return executeTool("query_rewrite", request);
    }

    @PostMapping("/judge-evidence-quality")
    public ToolCallResponse judgeEvidenceQuality(@RequestBody ToolCallRequest request) {
        return executeTool("judge_evidence_quality", request);
    }

    // --- Memory endpoints ---

    @PostMapping("/memory-recall")
    public ToolCallResponse memoryRecall(@RequestBody ToolCallRequest request) {
        try {
            Object result = memoryRecallService.recall(
                    request.userId, request.researchGroupId,
                    firstNonBlank(request.conversationId, request.workflowId),
                    request.input != null ? (String) request.input.getOrDefault("message", "") : "",
                    null);
            ToolCallResponse resp = new ToolCallResponse();
            resp.success = true;
            resp.data = result;
            return resp;
        } catch (Exception e) {
            log.error("Memory recall failed | workflowId={}", request.workflowId, e);
            ToolCallResponse resp = new ToolCallResponse();
            resp.success = false;
            resp.errorCode = "MEMORY_RECALL_ERROR";
            resp.errorMessage = e.getMessage();
            return resp;
        }
    }

    @PostMapping("/memory-writeback")
    public ToolCallResponse memoryWriteback(@RequestBody ToolCallRequest request) {
        try {
            String userMessage = request.input != null ? (String) request.input.getOrDefault("userMessage", "") : "";
            String finalAnswer = request.input != null ? (String) request.input.getOrDefault("finalAnswer", "") : "";
            memoryExtractionService.captureAfterWorkflow(
                    request.userId, request.researchGroupId,
                    firstNonBlank(request.conversationId, request.workflowId), request.workflowId,
                    userMessage, finalAnswer, null);
            ToolCallResponse resp = new ToolCallResponse();
            resp.success = true;
            resp.data = Map.of("status", "submitted");
            return resp;
        } catch (Exception e) {
            log.error("Memory writeback failed | workflowId={}", request.workflowId, e);
            ToolCallResponse resp = new ToolCallResponse();
            resp.success = false;
            resp.errorCode = "MEMORY_WRITEBACK_ERROR";
            resp.errorMessage = e.getMessage();
            return resp;
        }
    }

    // --- Helper ---

    private ToolCallResponse executeTool(String toolName, ToolCallRequest request) {
        log.info("Internal tool call | toolName={}, workflowId={}, agentName={}",
                toolName, request.workflowId, request.agentName);
        ToolContext context = ToolContext.of(
                request.workflowId, request.userId, request.researchGroupId, request.agentName);
        if (request.conversationId != null && !request.conversationId.isBlank()) {
            context.addMetadata("conversationId", request.conversationId);
        }
        if (request.stepId != null && !request.stepId.isBlank()) {
            context.addMetadata("stepId", request.stepId);
        }
        if (request.parentStepId != null && !request.parentStepId.isBlank()) {
            context.addMetadata("parentStepId", request.parentStepId);
        }
        if (request.routeDecision != null && !request.routeDecision.isEmpty()) {
            context.addMetadata("routeDecision", request.routeDecision);
        }
        if (request.contextBudget != null && !request.contextBudget.isEmpty()) {
            context.addMetadata("contextBudget", request.contextBudget);
        }
        ToolResult<?> result = toolRegistry.executeTool(toolName, request.input, context);
        ToolCallResponse response = ToolCallResponse.fromToolResult(result);
        Object rawPayload = result.isSuccess() ? result.getData() : Map.of(
                "errorCode", firstNonBlank(result.getErrorCode(), "TOOL_ERROR"),
                "errorMessage", firstNonBlank(result.getErrorMessage(), "Tool execution failed"));
        response.rawPayloadRef = rawPayloadStore.store(request.workflowId, toolName, rawPayload);
        return response;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? null : second.trim();
    }
}
