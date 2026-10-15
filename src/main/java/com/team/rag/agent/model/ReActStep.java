package com.team.rag.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * ReAct（Reason-Act-Observe）Agent 循环中的单步执行记录。
 *
 * <p>每一次 ReAct 循环（reason → tool → monitor）会产生一个 ReActStep，
 * 记录该步的推理摘要、执行动作、工具观察结果、监控判定、延迟和 token 消耗。
 *
 * <p>生命周期：RUNNING → SUCCESS / FAILED / SKIPPED
 *
 * <p>内部类结构：
 * <ul>
 *   <li>{@link ReActAction} — 执行动作（动作类型、工具名、输入参数）</li>
 *   <li>{@link Observation} — 工具执行结果（输出、摘要、状态、延迟）</li>
 *   <li>{@link MonitorResult} — 监控节点判定（是否继续、质量状态、警告、下一步提示）</li>
 * </ul>
 *
 * @see StepPhase — 步骤阶段枚举（REASON / ACT / OBSERVE / MONITOR）
 * @see StepStatus — 步骤状态枚举（RUNNING / SUCCESS / FAILED / SKIPPED）
 * @see QualityStatus — 质量判定枚举（PASS / WARNING / FAIL）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReActStep {

    @JsonProperty("stepId")
    private String stepId;

    @JsonProperty("workflowId")
    private String workflowId;

    @JsonProperty("agentProfile")
    private String agentProfile;

    @JsonProperty("stepIndex")
    private int stepIndex;

    @JsonProperty("phase")
    private StepPhase phase;

    @JsonProperty("reasoningSummary")
    private String reasoningSummary;

    @JsonProperty("action")
    private ReActAction action;

    @JsonProperty("observation")
    private Observation observation;

    @JsonProperty("monitorResult")
    private MonitorResult monitorResult;

    @JsonProperty("latencyMs")
    private long latencyMs;

    @JsonProperty("estimatedTokens")
    private int estimatedTokens;

    @JsonProperty("status")
    private StepStatus status;

    @JsonProperty("errorMessage")
    private String errorMessage;

    @JsonProperty("createdAt")
    private Instant createdAt;

    @JsonProperty("completedAt")
    private Instant completedAt;

    public ReActStep() {
        this.stepId = "step_" + UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = Instant.now();
        this.status = StepStatus.RUNNING;
    }

    public ReActStep(String workflowId, String agentProfile, int stepIndex) {
        this();
        this.workflowId = workflowId;
        this.agentProfile = agentProfile;
        this.stepIndex = stepIndex;
    }

    /** 工厂方法：创建一个新的 ReAct 步骤实例（状态为 RUNNING）。 */
    public static ReActStep create(String workflowId, String agentProfile, int stepIndex) {
        return new ReActStep(workflowId, agentProfile, stepIndex);
    }

    /** 记录推理阶段：LLM 的思考过程和决策摘要。 */
    public void recordReason(String reasoningSummary) {
        this.phase = StepPhase.REASON;
        this.reasoningSummary = reasoningSummary;
    }

    /** 记录动作阶段：LLM 选择的工具和输入参数。 */
    public void recordAction(ReActAction action) {
        this.phase = StepPhase.ACT;
        this.action = action;
    }

    /** 记录观察阶段：工具执行的返回结果。 */
    public void recordObservation(Observation observation) {
        this.phase = StepPhase.OBSERVE;
        this.observation = observation;
    }

    /** 记录监控阶段：预算门控和质量判定结果。 */
    public void recordMonitor(MonitorResult monitorResult) {
        this.phase = StepPhase.MONITOR;
        this.monitorResult = monitorResult;
    }

    /** 标记步骤为成功完成，记录延迟和 token 消耗。 */
    public void complete(long latencyMs, int estimatedTokens) {
        complete(latencyMs, estimatedTokens, StepStatus.SUCCESS);
    }

    public void complete(long latencyMs, int estimatedTokens, StepStatus status) {
        this.status = status == null ? StepStatus.SUCCESS : status;
        this.latencyMs = latencyMs;
        this.estimatedTokens = estimatedTokens;
        this.completedAt = Instant.now();
    }

    /** 标记步骤为失败，记录错误信息和延迟。 */
    public void fail(String errorMessage, long latencyMs) {
        this.status = StepStatus.FAILED;
        this.errorMessage = errorMessage;
        this.latencyMs = latencyMs;
        this.completedAt = Instant.now();
    }

    /** 标记步骤为跳过（如预算耗尽时跳过后续步骤）。 */
    public void skip(String reason) {
        this.status = StepStatus.SKIPPED;
        this.errorMessage = reason;
        this.completedAt = Instant.now();
    }

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getAgentProfile() { return agentProfile; }
    public void setAgentProfile(String agentProfile) { this.agentProfile = agentProfile; }

    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }

    public StepPhase getPhase() { return phase; }
    public void setPhase(StepPhase phase) { this.phase = phase; }

    public String getReasoningSummary() { return reasoningSummary; }
    public void setReasoningSummary(String reasoningSummary) { this.reasoningSummary = reasoningSummary; }

    public ReActAction getAction() { return action; }
    public void setAction(ReActAction action) { this.action = action; }

    public Observation getObservation() { return observation; }
    public void setObservation(Observation observation) { this.observation = observation; }

    public MonitorResult getMonitorResult() { return monitorResult; }
    public void setMonitorResult(MonitorResult monitorResult) { this.monitorResult = monitorResult; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public int getEstimatedTokens() { return estimatedTokens; }
    public void setEstimatedTokens(int estimatedTokens) { this.estimatedTokens = estimatedTokens; }

    public StepStatus getStatus() { return status; }
    public void setStatus(StepStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    /** ReAct 循环的四个阶段：REASON（推理）→ ACT（执行）→ OBSERVE（观察）→ MONITOR（监控判定）。 */
    public enum StepPhase {
        REASON("reason"),
        ACT("act"),
        OBSERVE("observe"),
        MONITOR("monitor");

        private final String description;

        StepPhase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /** 步骤状态：RUNNING（执行中）→ SUCCESS / FAILED / SKIPPED。 */
    public enum StepStatus {
        RUNNING("running"),
        SUCCESS("success"),
        FAILED("failed"),
        SKIPPED("skipped");

        private final String description;

        StepStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /** ReAct 动作：LLM 选择的执行动作（工具调用或直接回答）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReActAction {
        @JsonProperty("actionType")
        private String actionType;

        @JsonProperty("toolName")
        private String toolName;

        @JsonProperty("input")
        private Object input;

        @JsonProperty("description")
        private String description;

        public ReActAction() {}

        public String getActionType() { return actionType; }
        public void setActionType(String actionType) { this.actionType = actionType; }

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }

        public Object getInput() { return input; }
        public void setInput(Object input) { this.input = input; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /** 工具观察结果：工具执行的输出、摘要、状态和延迟。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Observation {
        @JsonProperty("toolName")
        private String toolName;

        @JsonProperty("output")
        private String output;

        @JsonProperty("summary")
        private String summary;

        @JsonProperty("status")
        private String status;

        @JsonProperty("latencyMs")
        private long latencyMs;

        public Observation() {}

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }

        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    }

    /** 监控节点判定结果：是否继续执行、质量状态、警告信息和下一步建议。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MonitorResult {
        @JsonProperty("shouldContinue")
        private boolean shouldContinue;

        @JsonProperty("qualityStatus")
        private QualityStatus qualityStatus;

        @JsonProperty("warnings")
        private java.util.List<String> warnings = new java.util.ArrayList<>();

        @JsonProperty("nextStepHint")
        private String nextStepHint;

        @JsonProperty("canFinish")
        private boolean canFinish;

        public MonitorResult() {}

        public MonitorResult(boolean shouldContinue, QualityStatus qualityStatus) {
            this.shouldContinue = shouldContinue;
            this.qualityStatus = qualityStatus;
        }

        public boolean isShouldContinue() { return shouldContinue; }
        public void setShouldContinue(boolean shouldContinue) { this.shouldContinue = shouldContinue; }

        public QualityStatus getQualityStatus() { return qualityStatus; }
        public void setQualityStatus(QualityStatus qualityStatus) { this.qualityStatus = qualityStatus; }

        public java.util.List<String> getWarnings() { return warnings; }
        public void setWarnings(java.util.List<String> warnings) { this.warnings = warnings; }

        public String getNextStepHint() { return nextStepHint; }
        public void setNextStepHint(String nextStepHint) { this.nextStepHint = nextStepHint; }

        public boolean isCanFinish() { return canFinish; }
        public void setCanFinish(boolean canFinish) { this.canFinish = canFinish; }
    }

    /** 质量判定：PASS（通过）/ WARNING（警告，可继续）/ FAIL（失败，应停止）。 */
    public enum QualityStatus {
        PASS("pass"),
        WARNING("warning"),
        FAIL("fail");

        private final String description;

        QualityStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
