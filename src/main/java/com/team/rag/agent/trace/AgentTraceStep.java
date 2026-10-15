package com.team.rag.agent.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Agent 执行追踪步骤
 *
 * <p>记录 Agent 执行过程中的每一步操作（Agent 调用、工具调用、监控检查），
 * 构成完整的执行轨迹。每步包含输入/输出预览、耗时、token 估算等信息。</p>
 *
 * <p><b>用途：</b></p>
 * <ul>
 *   <li>调试和问题排查 — 通过 traceId 追踪完整执行链</li>
 *   <li>性能分析 — latencyMs 和 token 估算</li>
 *   <li>可视化展示 — 前端 Workbench 的步骤时间线</li>
 *   <li>成本核算 — 累计 token 消耗</li>
 * </ul>
 *
 * @see AgentTraceService — 追踪服务，持久化追踪步骤
 * @see TraceStatus — 步骤状态枚举
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentTraceStep {

    /** 追踪唯一标识，格式如 "react_a3f8_agent_ClaimDecomposer_1716000000000" */
    @JsonProperty("traceId")
    private String traceId;

    /** 关联的工作流 ID */
    @JsonProperty("workflowId")
    private String workflowId;

    /** 父追踪步骤 ID（用于 Handoff 场景，子 Agent 步骤指向父 Agent） */
    @JsonProperty("parentTraceId")
    private String parentTraceId;

    /** 步骤在工作流中的序号（从 0 开始） */
    @JsonProperty("stepIndex")
    private int stepIndex;

    /** 步骤类型：AGENT / TOOL / MONITOR / NODE / ROUTER / PIPELINE / MEMORY / LIFECYCLE */
    @JsonProperty("stepType")
    private StepType stepType;

    /** 执行此步骤的 Agent 名称（如 "ClaimDecomposerAgent"） */
    @JsonProperty("agentName")
    private String agentName;

    /** 工具名称（仅 TOOL 类型步骤有值，如 "retrieve_evidence"） */
    @JsonProperty("toolName")
    private String toolName;

    /** 前端显示名称（如 "证据检索"、"声明分解"） */
    @JsonProperty("displayName")
    private String displayName;

    /** 执行阶段标识（如 "reason"、"act"、"observe"） */
    @JsonProperty("phase")
    private String phase;

    /** 步骤摘要（截断到 180 字符，用于列表展示） */
    @JsonProperty("summary")
    private String summary;

    /** 状态原因说明（如 "completed"、"budget_exhausted"） */
    @JsonProperty("statusReason")
    private String statusReason;

    /** 使用的 Prompt 模板 ID（用于 prompt 版本追踪） */
    @JsonProperty("promptId")
    private String promptId;

    /** Prompt 模板版本号 */
    @JsonProperty("promptVersion")
    private String promptVersion;

    /** 输入预览（截断到 500 字符） */
    @JsonProperty("inputPreview")
    private String inputPreview;

    /** 输出预览（截断到 500 字符） */
    @JsonProperty("outputPreview")
    private String outputPreview;

    /** 完整输入文本（用于调试，可能很长） */
    @JsonProperty("fullInput")
    private String fullInput;

    /** 完整输出文本（用于调试，可能很长） */
    @JsonProperty("fullOutput")
    private String fullOutput;

    /** 步骤状态：RUNNING / SUCCESS / FAILED / SKIPPED / TIMEOUT */
    @JsonProperty("status")
    private TraceStatus status;

    /** 执行耗时（毫秒） */
    @JsonProperty("latencyMs")
    private long latencyMs;

    /** 估算的输入 token 数（中文 1 token ≈ 2 字符，英文 1 token ≈ 4 字符） */
    @JsonProperty("estimatedInputTokens")
    private int estimatedInputTokens;

    /** 估算的输出 token 数 */
    @JsonProperty("estimatedOutputTokens")
    private int estimatedOutputTokens;

    /** 失败时的错误信息 */
    @JsonProperty("errorMessage")
    private String errorMessage;

    /** 步骤创建时间 */
    @JsonProperty("createdAt")
    private Instant createdAt;

    /** 步骤完成时间 */
    @JsonProperty("completedAt")
    private Instant completedAt;

    /** 附加元数据（如 skipReason、handoffTarget 等） */
    @JsonProperty("metadata")
    private java.util.Map<String, Object> metadata;

    // Constructors
    public AgentTraceStep() {
        this.createdAt = Instant.now();
        this.status = TraceStatus.RUNNING;
        this.metadata = new java.util.HashMap<>();
    }

    public AgentTraceStep(String workflowId, StepType stepType, String agentName) {
        this();
        this.workflowId = workflowId;
        this.stepType = stepType;
        this.agentName = agentName;
        this.traceId = generateTraceId(workflowId, stepType, agentName);
    }

    // Static factory methods
    public static AgentTraceStep agentStep(String workflowId, String agentName) {
        return new AgentTraceStep(workflowId, StepType.AGENT, agentName);
    }

    public static AgentTraceStep toolStep(String workflowId, String toolName) {
        AgentTraceStep step = new AgentTraceStep(workflowId, StepType.TOOL, null);
        step.setToolName(toolName);
        return step;
    }

    public static AgentTraceStep monitorStep(String workflowId) {
        return new AgentTraceStep(workflowId, StepType.MONITOR, null);
    }

    public static AgentTraceStep executionStep(String workflowId, StepType stepType, String name) {
        return new AgentTraceStep(workflowId, stepType, name);
    }

    // Complete the trace step
    public void complete(String output, long latencyMs) {
        this.status = TraceStatus.SUCCESS;
        this.outputPreview = truncate(output, 500);
        this.fullOutput = output;
        this.summary = truncate(output, 180);
        this.statusReason = "completed";
        this.latencyMs = latencyMs;
        this.completedAt = Instant.now();
        this.estimatedOutputTokens = estimateTokens(output);
    }

    public void fail(String errorMessage, long latencyMs) {
        this.status = TraceStatus.FAILED;
        this.errorMessage = errorMessage;
        this.summary = truncate(errorMessage, 180);
        this.statusReason = errorMessage;
        this.latencyMs = latencyMs;
        this.completedAt = Instant.now();
    }

    public void skip(String reason) {
        this.status = TraceStatus.SKIPPED;
        this.summary = truncate(reason, 180);
        this.statusReason = reason;
        this.metadata.put("skipReason", reason);
        this.completedAt = Instant.now();
    }

    // Token estimation (rough: 1 token ≈ 4 chars for English, 2 chars for Chinese)
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.toString(c).matches("[\\u4e00-\\u9fa5]")) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (chineseChars / 2) + (otherChars / 4);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private String generateTraceId(String workflowId, StepType stepType, String agentName) {
        return workflowId + "_" + stepType.name().toLowerCase() + "_" +
               (agentName != null ? agentName : "step") + "_" +
               System.currentTimeMillis();
    }

    // Getters and Setters
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getParentTraceId() { return parentTraceId; }
    public void setParentTraceId(String parentTraceId) { this.parentTraceId = parentTraceId; }

    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }

    public StepType getStepType() { return stepType; }
    public void setStepType(StepType stepType) { this.stepType = stepType; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public String getPromptId() { return promptId; }
    public void setPromptId(String promptId) { this.promptId = promptId; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getInputPreview() { return inputPreview; }
    public void setInputPreview(String inputPreview) { this.inputPreview = inputPreview; }

    public String getOutputPreview() { return outputPreview; }
    public void setOutputPreview(String outputPreview) { this.outputPreview = outputPreview; }

    public String getFullInput() { return fullInput; }
    public void setFullInput(String fullInput) {
        this.fullInput = fullInput;
        this.inputPreview = truncate(fullInput, 500);
        this.estimatedInputTokens = estimateTokens(fullInput);
    }

    public String getFullOutput() { return fullOutput; }
    public void setFullOutput(String fullOutput) {
        this.fullOutput = fullOutput;
        this.outputPreview = truncate(fullOutput, 500);
        this.estimatedOutputTokens = estimateTokens(fullOutput);
    }

    public TraceStatus getStatus() { return status; }
    public void setStatus(TraceStatus status) { this.status = status; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public int getEstimatedInputTokens() { return estimatedInputTokens; }
    public void setEstimatedInputTokens(int tokens) { this.estimatedInputTokens = tokens; }

    public int getEstimatedOutputTokens() { return estimatedOutputTokens; }
    public void setEstimatedOutputTokens(int tokens) { this.estimatedOutputTokens = tokens; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public java.util.Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(java.util.Map<String, Object> metadata) { this.metadata = metadata; }
    public void addMetadata(String key, Object value) { this.metadata.put(key, value); }

    /**
     * 步骤类型枚举
     */
    public enum StepType {
        AGENT("Agent 执行"),
        TOOL("工具调用"),
        MONITOR("监控检查"),
        NODE("工作流节点"),
        ROUTER("路由决策"),
        PIPELINE("执行管线"),
        MEMORY("记忆处理"),
        LIFECYCLE("生命周期");

        private final String description;

        StepType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
