package com.team.rag.agent.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent 执行追踪服务
 *
 * <p>管理 Agent 执行过程中的所有追踪步骤，支持：
 * <ul>
 *   <li>记录每个 Agent 和工具调用的输入输出</li>
 *   <li>计算 token 消耗和延迟</li>
 *   <li>按 workflowId 查询完整的执行路径</li>
 *   <li>生成执行统计报告</li>
 * </ul>
 */
@Service
public class AgentTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceService.class);

    private static final String TRACE_KEY_PREFIX = "agent:trace:";
    private static final String WORKFLOW_TRACES_KEY_PREFIX = "agent:workflow:traces:";
    private static final Duration TRACE_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // In-memory cache for current session traces
    private final Map<String, List<AgentTraceStep>> sessionTraces = new ConcurrentHashMap<>();
    private final Map<String, Integer> stepCounters = new ConcurrentHashMap<>();

    public AgentTraceService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        log.info("AgentTraceService 初始化完成");
    }

    /**
     * 开始新的 workflow 追踪
     *
     * @param workflowId workflow ID
     * @return 初始的 trace step
     */
    public AgentTraceStep startWorkflow(String workflowId) {
        log.info("开始 workflow 追踪 | workflowId={}", workflowId);

        stepCounters.putIfAbsent(workflowId, 0);
        List<AgentTraceStep> existing = sessionTraces.computeIfAbsent(workflowId, k -> new ArrayList<>());
        boolean hasStart = existing.stream()
                .anyMatch(step -> "workflow_start".equals(step.getMetadata().get("event")));
        if (hasStart) {
            log.debug("workflow 追踪已存在，跳过重复 start | workflowId={}", workflowId);
            return existing.get(0);
        }

        AgentTraceStep step = AgentTraceStep.executionStep(
                workflowId, AgentTraceStep.StepType.LIFECYCLE, "WorkflowStart");
        step.setStepIndex(0);
        step.setDisplayName("Workflow Start");
        step.setPhase("lifecycle");
        step.setSummary("Workflow started");
        step.addMetadata("event", "workflow_start");
        step.complete("Workflow started", 0);

        recordStep(step);
        return step;
    }

    /**
     * 记录 Agent 执行步骤
     *
     * @param workflowId  workflow ID
     * @param agentName   Agent 名称
     * @param input       输入内容
     * @param output      输出内容
     * @param latencyMs   延迟（毫秒）
     * @param promptId    Prompt ID
     * @param promptVersion Prompt 版本
     * @return 记录的 trace step
     */
    public AgentTraceStep recordAgentStep(String workflowId, String agentName,
                                           String input, String output,
                                           long latencyMs,
                                           String promptId, String promptVersion) {
        AgentTraceStep step = AgentTraceStep.agentStep(workflowId, agentName);
        step.setDisplayName(agentName);
        step.setPhase("agent");
        step.setFullInput(input);
        step.setFullOutput(output);
        step.setLatencyMs(latencyMs);
        step.setPromptId(promptId);
        step.setPromptVersion(promptVersion);
        step.complete(output, latencyMs);

        recordStep(step);
        log.debug("记录 Agent 步骤 | agent={}, latency={}ms", agentName, latencyMs);
        return step;
    }

    /** Records a non-Agent execution stage such as a router, node or pipeline. */
    public AgentTraceStep recordExecutionStep(String workflowId,
                                              AgentTraceStep.StepType stepType,
                                              String displayName,
                                              String phase,
                                              String input,
                                              String output,
                                              long latencyMs) {
        AgentTraceStep step = AgentTraceStep.executionStep(workflowId, stepType, null);
        step.setDisplayName(displayName);
        step.setPhase(phase);
        step.setFullInput(input);
        step.setFullOutput(output);
        step.setLatencyMs(latencyMs);
        step.complete(output, latencyMs);
        recordStep(step);
        return step;
    }

    /**
     * 记录工具调用步骤
     *
     * @param workflowId workflow ID
     * @param toolName   工具名称
     * @param input      输入内容
     * @param output     输出内容
     * @param latencyMs  延迟（毫秒）
     * @return 记录的 trace step
     */
    public AgentTraceStep recordToolStep(String workflowId, String toolName,
                                          String input, String output,
                                          long latencyMs) {
        AgentTraceStep step = AgentTraceStep.toolStep(workflowId, toolName);
        step.setDisplayName(toolName);
        step.setPhase("tool");
        step.setFullInput(input);
        step.setFullOutput(output);
        step.setLatencyMs(latencyMs);
        step.complete(output, latencyMs);

        recordStep(step);
        log.debug("记录工具调用 | tool={}, latency={}ms", toolName, latencyMs);
        return step;
    }

    /**
     * 记录失败步骤
     *
     * @param workflowId  workflow ID
     * @param stepType    步骤类型
     * @param name        名称
     * @param input       输入内容
     * @param errorMessage 错误信息
     * @param latencyMs   延迟（毫秒）
     * @return 记录的 trace step
     */
    public AgentTraceStep recordFailure(String workflowId, AgentTraceStep.StepType stepType,
                                         String name, String input,
                                         String errorMessage, long latencyMs) {
        AgentTraceStep step;
        if (stepType == AgentTraceStep.StepType.TOOL) {
            step = AgentTraceStep.toolStep(workflowId, name);
            step.setPhase("tool");
        } else {
            step = AgentTraceStep.agentStep(workflowId, name);
            step.setPhase(stepType == AgentTraceStep.StepType.MONITOR ? "monitor" : "agent");
        }
        step.setDisplayName(name);
        step.setFullInput(input);
        step.fail(errorMessage, latencyMs);

        recordStep(step);
        log.warn("记录失败步骤 | type={}, name={}, error={}", stepType, name, errorMessage);
        return step;
    }

    /**
     * 记录 MonitorAgent 检查步骤
     *
     * @param workflowId workflow ID
     * @param input      输入内容
     * @param output     检查结果
     * @param latencyMs  延迟（毫秒）
     * @return 记录的 trace step
     */
    public AgentTraceStep recordMonitorStep(String workflowId, String input,
                                             String output, long latencyMs) {
        AgentTraceStep step = AgentTraceStep.monitorStep(workflowId);
        step.setDisplayName("MonitorAgent");
        step.setPhase("monitor");
        step.setFullInput(input);
        step.setFullOutput(output);
        step.setLatencyMs(latencyMs);
        step.complete(output, latencyMs);

        recordStep(step);
        log.debug("记录 Monitor 检查 | latency={}ms", latencyMs);
        return step;
    }

    /**
     * 结束 workflow 追踪
     *
     * @param workflowId workflow ID
     * @param status     最终状态
     * @param summary    执行摘要
     */
    public void finishWorkflow(String workflowId, String status, String summary) {
        log.info("结束 workflow 追踪 | workflowId={}, status={}", workflowId, status);

        AgentTraceStep step = AgentTraceStep.executionStep(
                workflowId, AgentTraceStep.StepType.LIFECYCLE, "WorkflowEnd");
        step.setDisplayName("Workflow End");
        step.setPhase("lifecycle");
        step.setSummary(summary);
        step.addMetadata("event", "workflow_end");
        step.addMetadata("finalStatus", status);
        step.addMetadata("summary", summary);
        step.complete(summary, 0);

        recordStep(step);

        // Persist to Redis
        persistToRedis(workflowId);
    }

    /**
     * 获取 workflow 的所有追踪步骤
     *
     * @param workflowId workflow ID
     * @return 追踪步骤列表
     */
    public List<AgentTraceStep> getWorkflowTraces(String workflowId) {
        // Try session cache first
        List<AgentTraceStep> traces = sessionTraces.get(workflowId);
        if (traces != null && !traces.isEmpty()) {
            return new ArrayList<>(traces);
        }

        // Try Redis
        return loadFromRedis(workflowId);
    }

    /**
     * Replace all traces for a workflow.
     *
     * <p>Used when an external runtime (for example Python LangGraph) sends back a
     * fully materialized trace list and Java should treat that list as the source of truth.</p>
     */
    public void setWorkflowTraces(String workflowId, List<AgentTraceStep> traces) {
        if (workflowId == null || workflowId.isBlank()) {
            return;
        }
        List<AgentTraceStep> safeTraces = traces == null ? new ArrayList<>() : new ArrayList<>(traces);
        sessionTraces.put(workflowId, safeTraces);
        int maxIndex = safeTraces.stream().mapToInt(AgentTraceStep::getStepIndex).max().orElse(0);
        stepCounters.put(workflowId, maxIndex);
        persistToRedis(workflowId);
    }

    /**
     * 获取 workflow 的执行统计
     *
     * @param workflowId workflow ID
     * @return 统计信息
     */
    public TraceStatistics getWorkflowStatistics(String workflowId) {
        List<AgentTraceStep> traces = getWorkflowTraces(workflowId);
        if (traces.isEmpty()) {
            return new TraceStatistics();
        }

        TraceStatistics stats = new TraceStatistics();
        stats.totalSteps = traces.size();
        stats.successfulSteps = traces.stream().filter(t -> t.getStatus() == TraceStatus.SUCCESS).count();
        stats.failedSteps = traces.stream().filter(t -> t.getStatus() == TraceStatus.FAILED).count();
        stats.totalLatencyMs = traces.stream().mapToLong(AgentTraceStep::getLatencyMs).sum();
        stats.totalInputTokens = traces.stream().mapToInt(AgentTraceStep::getEstimatedInputTokens).sum();
        stats.totalOutputTokens = traces.stream().mapToInt(AgentTraceStep::getEstimatedOutputTokens).sum();

        stats.agentSteps = traces.stream().filter(t -> t.getStepType() == AgentTraceStep.StepType.AGENT).count();
        stats.toolSteps = traces.stream().filter(t -> t.getStepType() == AgentTraceStep.StepType.TOOL).count();
        stats.monitorSteps = traces.stream().filter(t -> t.getStepType() == AgentTraceStep.StepType.MONITOR).count();
        stats.nodeSteps = traces.stream().filter(t -> t.getStepType() == AgentTraceStep.StepType.NODE).count();
        stats.pipelineSteps = traces.stream().filter(t -> t.getStepType() == AgentTraceStep.StepType.PIPELINE).count();
        stats.routingSteps = traces.stream().filter(t -> t.getStepType() == AgentTraceStep.StepType.ROUTER).count();
        stats.memorySteps = traces.stream().filter(t -> t.getStepType() == AgentTraceStep.StepType.MEMORY).count();

        // Tool success rate
        long toolSuccess = traces.stream()
                .filter(t -> t.getStepType() == AgentTraceStep.StepType.TOOL && t.getStatus() == TraceStatus.SUCCESS)
                .count();
        stats.toolSuccessRate = stats.toolSteps > 0 ? (double) toolSuccess / stats.toolSteps : 1.0;

        return stats;
    }

    /**
     * 内部方法：记录步骤
     */
    private void recordStep(AgentTraceStep step) {
        int stepIndex = stepCounters.compute(step.getWorkflowId(), (k, v) -> v == null ? 1 : v + 1);
        step.setStepIndex(stepIndex);

        sessionTraces.computeIfAbsent(step.getWorkflowId(), k -> new ArrayList<>()).add(step);
    }

    /**
     * 持久化到 Redis
     */
    private void persistToRedis(String workflowId) {
        try {
            List<AgentTraceStep> traces = sessionTraces.get(workflowId);
            if (traces == null || traces.isEmpty()) {
                return;
            }

            String json = objectMapper.writeValueAsString(traces);
            String key = TRACE_KEY_PREFIX + workflowId;

            redisTemplate.opsForValue().set(key, json, TRACE_TTL);
            log.debug("Trace 已持久化到 Redis | workflowId={}, steps={}", workflowId, traces.size());

        } catch (JsonProcessingException e) {
            log.error("序列化 Trace 失败 | workflowId={}, error={}", workflowId, e.getMessage());
        } catch (Exception e) {
            log.warn("Trace Redis persistence disabled | workflowId={}, error={}", workflowId, e.getMessage());
        }
    }

    /**
     * 从 Redis 加载
     */
    private List<AgentTraceStep> loadFromRedis(String workflowId) {
        try {
            String key = TRACE_KEY_PREFIX + workflowId;
            String json = redisTemplate.opsForValue().get(key);

            if (json == null || json.isEmpty()) {
                return Collections.emptyList();
            }

            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AgentTraceStep.class));

        } catch (Exception e) {
            log.error("从 Redis 加载 Trace 失败 | workflowId={}, error={}", workflowId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 执行统计内部类
     */
    public static class TraceStatistics {
        public long totalSteps;
        public long successfulSteps;
        public long failedSteps;
        public long totalLatencyMs;
        public int totalInputTokens;
        public int totalOutputTokens;
        public long agentSteps;
        public long toolSteps;
        public long monitorSteps;
        public long nodeSteps;
        public long pipelineSteps;
        public long routingSteps;
        public long memorySteps;
        public double toolSuccessRate;

        public int getTotalTokens() {
            return totalInputTokens + totalOutputTokens;
        }

        public double getSuccessRate() {
            return totalSteps > 0 ? (double) successfulSteps / totalSteps : 0;
        }
    }
}
