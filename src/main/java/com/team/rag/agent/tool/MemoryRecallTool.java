package com.team.rag.agent.tool;

import com.team.rag.agent.runtime.MemoryRecallRecord;
import com.team.rag.service.DualEngineMemoryManager;
import com.team.rag.service.MemoryRecallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 记忆召回工具 —— 从用户的多层记忆系统中检索相关上下文。
 *
 * <p>在 ReAct Agent 的推理循环中，当需要了解用户历史、偏好或相关记忆时调用。
 * 综合短期对话摘要、语义记忆和项目记忆三个维度。
 */
@Component
public class MemoryRecallTool implements ScholarAgentTool<MemoryRecallTool.Input, MemoryRecallTool.Output> {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallTool.class);

    private final MemoryRecallService recallService;
    private final DualEngineMemoryManager memoryManager;

    public MemoryRecallTool(MemoryRecallService recallService, DualEngineMemoryManager memoryManager) {
        this.recallService = recallService;
        this.memoryManager = memoryManager;
    }

    @Override
    public String name() {
        return "memory_recall";
    }

    @Override
    public String description() {
        return "Recall user memories and conversation context. Use when you need to understand " +
                "the user's research history, preferences, or previous interactions. " +
                "Returns structured profile, semantic memories, and recent conversation summary.";
    }

    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("Memory recall input");
        schema.addProperty("query", ToolSchema.PropertySchema.string(
                "The current user query or topic to recall memories about."));
        schema.addProperty("conversationId", ToolSchema.PropertySchema.string(
                "Conversation ID for short-term memory recall."));
        schema.addProperty("mode", ToolSchema.PropertySchema.string(
                "Recall mode: 'full' (all layers), 'structured' (profile only), 'semantic' (search by query). Default 'full'.")
                .enumValues("full", "structured", "semantic"));
        schema.addProperty("maxSemanticResults", ToolSchema.PropertySchema.integer(
                "Maximum semantic memory results. Default 6."));
        schema.required("query");
        return schema;
    }

    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("Memory recall output");
        schema.addProperty("structuredProfile", ToolSchema.PropertySchema.object("User structured profile data."));
        schema.addProperty("semanticMemories", ToolSchema.PropertySchema.array("Relevant semantic memories."));
        schema.addProperty("conversationSummary", ToolSchema.PropertySchema.string("Short-term conversation summary."));
        schema.addProperty("diagnostics", ToolSchema.PropertySchema.object("Recall diagnostics."));
        return schema;
    }

    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        long start = System.currentTimeMillis();
        if (input == null || input.query == null || input.query.isBlank()) {
            return ToolResult.failure("INVALID_INPUT", "query is required", System.currentTimeMillis() - start);
        }

        String userId = context != null ? context.getUserId() : "user-001";
        String researchGroupId = context != null ? context.getResearchGroupId() : "replace-me";
        String conversationId = input.conversationId;
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = context != null ? context.getWorkflowId() : "default";
        }

        String mode = input.mode != null ? input.mode : "full";
        int maxSemantic = input.maxSemanticResults != null ? input.maxSemanticResults : 6;

        try {
            Output output = new Output();

            if ("structured".equals(mode) || "full".equals(mode)) {
                output.structuredProfile = memoryManager.getStructuredProfile(userId);
            }

            if ("semantic".equals(mode) || "full".equals(mode)) {
                output.semanticMemories = memoryManager.searchSemanticMemories(
                        userId, input.query, maxSemantic, 0.3);
            }

            if ("full".equals(mode)) {
                try {
                    MemoryRecallRecord record = recallService.recall(
                            userId, researchGroupId, conversationId, input.query, null);
                    output.conversationSummary = record.shortTermSummary;
                    output.diagnostics = record.diagnostics;
                } catch (Exception e) {
                    log.warn("Memory recall partial failure | userId={}, error={}", userId, e.getMessage());
                    output.diagnostics = Map.of("error", e.getMessage());
                }
            }

            long latency = System.currentTimeMillis() - start;
            return ToolResult.success(output, latency);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Memory recall error | userId={}, error={}", userId, e.getMessage());
            return ToolResult.failure("MEMORY_ERROR", "Memory recall error: " + e.getMessage(), latency);
        }
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public ToolType type() {
        return ToolType.MEMORY;
    }

    @Override
    public int priority() {
        return 1;
    }

    public static class Input {
        public String query;
        public String conversationId;
        public String mode;
        public Integer maxSemanticResults;
    }

    public static class Output {
        public Map<Object, Object> structuredProfile;
        public List<Map<String, Object>> semanticMemories;
        public String conversationSummary;
        public Object diagnostics;
    }
}
