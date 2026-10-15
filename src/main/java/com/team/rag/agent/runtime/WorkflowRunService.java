package com.team.rag.agent.runtime;

import com.team.rag.agent.context.ContextCompileRequest;
import com.team.rag.agent.context.ContextCompilerService;
import com.team.rag.agent.context.ContextEnvelope;
import com.team.rag.agent.routing.ExecutionDecision;
import com.team.rag.agent.routing.ExecutionDecisionService;
import com.team.rag.agent.routing.HybridRouteDecision;
import com.team.rag.agent.trace.AgentTraceService;
import com.team.rag.agent.trace.AgentTraceStep;
import com.team.rag.service.MemoryRecallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Unified workflow lifecycle coordinator.
 *
 * <h3>核心职责</h3>
 * <ol>
 *   <li>接收前端/调用方提交的 {@link WorkflowRunRequest}，生成全局唯一的 workflowId 作为本次运行的追踪标识；</li>
 *   <li>调用 {@link ExecutionDecisionService} 生成单一、可审计的执行决策；</li>
 *   <li>调用 {@link MemoryRecallService} 召回记忆，并通过 {@link ContextCompilerService} 编译受预算约束的上下文；</li>
 *   <li>根据执行决策分发：Quick QA 在 Java 执行，Evidence Workflow/DeepResearch 通过 gRPC 进入 Python LangGraph；</li>
 *   <li>执行完成后委托后处理和持久化服务完成诊断、记忆回写、快照与 SSE 事件。</li>
 * </ol>
 *
 * <h3>设计思想</h3>
 * <ul>
 *   <li><b>事件驱动</b>：通过 {@link WorkflowEventBus} 发布 SSE 事件，前端可实时展示工作流进度；</li>
 *   <li><b>快照持久化</b>：每次状态变更都会通过 Redis 持久化快照，保证服务重启后可恢复；</li>
 *   <li><b>异步执行</b>：{@code startRun()} 立即返回快照，实际执行通过线程池异步进行；</li>
 *   <li><b>Redis 容错</b>：Redis 不可用时自动降级为纯内存模式，不会阻断主流程。</li>
 * </ul>
 *
 * @see ExecutionDecisionService 执行决策
 * @see MemoryRecallService 记忆召回
 * @see ContextCompilerService 上下文编译与预算管理
 * @see QuickQaExecutionService 快速问答执行
 * @see ExecutionRuntimeSelector Java/Python 运行时选择
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);
    private static final int CONTROL_HISTORY_LIMIT = 40;

    /** 链路追踪服务：记录每个 step 的输入/输出/耗时，支持性能分析和调试 */
    private final AgentTraceService traceService;
    /** SSE 事件总线：发布实时事件给前端，驱动工作流进度展示 */
    private final WorkflowEventBus eventBus;
    /** RAG 文献对话服务：基于检索增强生成的文献问答 */
    private final QuickQaExecutionService quickQaExecutionService;
    /** Product-level authoritative execution decision service. */
    private final ExecutionDecisionService executionDecisionService;
    /** 记忆召回服务：根据当前消息召回相关的历史记忆片段 */
    private final MemoryRecallService memoryRecallService;
    private final ContextCompilerService contextCompilerService;
    private final WorkflowMetadataService metadataService;
    private final WorkflowPersistenceService persistenceService;
    private final WorkflowPostProcessingService postProcessingService;
    private final WorkflowSnapshotHydrator snapshotHydrator;
    /** 运行时模式解析：决定请求走 Java 还是 Python */
    private final ExecutionRuntimeSelector runtimeSelector;
    private final PythonWorkflowDispatchService pythonDispatchService;

    // ==================== 运行时状态 ====================

    /** Bounded workflow executor. */
    private final ThreadPoolTaskExecutor executor;

    /** Constructor injection keeps runtime dependencies explicit and testable. */
    @Autowired
    public WorkflowRunService(
            AgentTraceService traceService,
            WorkflowEventBus eventBus,
            QuickQaExecutionService quickQaExecutionService,
            ExecutionDecisionService executionDecisionService,
            MemoryRecallService memoryRecallService,
            ContextCompilerService contextCompilerService,
            WorkflowMetadataService metadataService,
            WorkflowPersistenceService persistenceService,
            WorkflowPostProcessingService postProcessingService,
            WorkflowSnapshotHydrator snapshotHydrator,
            ExecutionRuntimeSelector runtimeSelector,
            PythonWorkflowDispatchService pythonDispatchService,
            @Qualifier("workflowTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.traceService = traceService;
        this.eventBus = eventBus;
        this.quickQaExecutionService = quickQaExecutionService;
        this.executionDecisionService = executionDecisionService;
        this.memoryRecallService = memoryRecallService;
        this.contextCompilerService = contextCompilerService;
        this.metadataService = metadataService;
        this.persistenceService = persistenceService;
        this.postProcessingService = postProcessingService;
        this.snapshotHydrator = snapshotHydrator;
        this.runtimeSelector = runtimeSelector;
        this.pythonDispatchService = pythonDispatchService;
        this.executor = executor;
    }

    /**
     * 启动一次工作流运行。这是整个系统的统一入口。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>生成全局唯一的 workflowId（格式：wf_时间戳_随机串）；</li>
     *   <li>调用 {@link ExecutionDecisionService} 选择产品策略和运行时；</li>
     *   <li>创建快照并持久化到 Redis；</li>
     *   <li>提交异步任务到线程池，立即返回当前快照给调用方。</li>
     * </ol>
     *
     * @param request 工作流请求，包含 message、userId、engine 等参数；允许为 null（会使用默认值）
     * @return 当前快照，状态为 RUNNING（实际执行是异步的）
     */
    public WorkflowRunSnapshot startRun(WorkflowRunRequest request) {
        // 使用时间戳+随机串保证全局唯一，避免分布式环境下的 ID 冲突
        String workflowId = "wf_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        // 防御性编程：request 为 null 时使用空对象，避免后续 NPE
        WorkflowRunRequest safeRequest = request == null ? new WorkflowRunRequest() : request;
        // 路由决策：综合用户消息、请求的引擎偏好，决定实际使用的引擎和 profile
        ExecutionDecision executionDecision = executionDecisionService.decide(
                safeRequest.message,
                requestedMode(safeRequest),
                defaultString(safeRequest.profile, "auto")
        );
        HybridRouteDecision routeDecision = executionDecision.toLegacy();

        // 创建快照对象，记录本次运行的初始状态
        WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot();
        snapshot.workflowId = workflowId;
        snapshot.engine = routeDecision.engine;
        snapshot.runtimeTarget = metadataService.inferRuntimeTarget(routeDecision);
        snapshot.status = "RUNNING";
        snapshot.createdAt = Instant.now();
        snapshot.routeDecision = routeDecision;
        snapshot.executionDecision = executionDecision;
        snapshot.metadata = metadataService.createInitialMetadata(
                workflowId, safeRequest, routeDecision, snapshot.runtimeTarget);
        persistenceService.put(snapshot);
        persistenceService.save(snapshot); // 先持久化，确保即使服务崩溃也能恢复

        // 异步执行实际工作流，startRun 立即返回，不阻塞调用方
        executor.submit(() -> runAsync(workflowId, safeRequest, routeDecision));
        return getSnapshot(workflowId);
    }

    private String requestedMode(WorkflowRunRequest request) {
        if (request.engine != null && !request.engine.isBlank() && !"auto".equalsIgnoreCase(request.engine)) {
            return request.engine;
        }
        return defaultString(request.mode, "auto");
    }

    /**
     * 获取指定工作流的快照，用于前端轮询或状态查询。
     *
     * <p>查找优先级：内存缓存 → Redis → 返回 UNKNOWN 状态的空快照。
     * 从 Redis 加载的快照会自动回填到内存缓存中，加速后续查询。</p>
     *
     * @param workflowId 工作流唯一标识
     * @return 工作流快照，包含当前状态、事件列表、追踪信息等
     */
    public WorkflowRunSnapshot getSnapshot(String workflowId) {
        WorkflowRunSnapshot snapshot = persistenceService.find(workflowId);
        if (snapshot == null) {
            snapshot = new WorkflowRunSnapshot();
            snapshot.workflowId = workflowId;
            snapshot.status = "UNKNOWN";
        }
        // 填充动态数据（事件、追踪、步骤等），这些数据存储在各自的独立存储中
        snapshotHydrator.hydrate(snapshot);
        return snapshot;
    }

    /**
     * Records a control-plane command into snapshot history so interrupt/resume decisions are auditable.
     */
    public void recordControlCommand(String workflowId,
                                     String command,
                                     String publishStatus,
                                     Map<String, Object> payload,
                                     String detail) {
        WorkflowRunSnapshot snapshot = persistenceService.find(workflowId);
        if (snapshot == null) {
            return;
        }

        List<Map<String, Object>> history = snapshot.controlHistory == null
                ? new ArrayList<>()
                : new ArrayList<>(snapshot.controlHistory);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("at", Instant.now().toString());
        item.put("command", command);
        item.put("status", publishStatus);
        item.put("detail", defaultString(detail, ""));
        item.put("payload", payload == null ? Map.of() : payload);
        history.add(item);
        if (history.size() > CONTROL_HISTORY_LIMIT) {
            history = history.subList(history.size() - CONTROL_HISTORY_LIMIT, history.size());
        }
        snapshot.controlHistory = new ArrayList<>(history);

        snapshot.runtimeMetadata = metadataService.mergeMetadata(snapshot.runtimeMetadata, Map.of(
                "lastControlCommand", command,
                "lastControlStatus", publishStatus,
                "lastControlAt", item.get("at")
        ));
        snapshot.metadata = metadataService.mergeMetadata(snapshot.metadata, Map.of(
                "controlPending", "REQUESTED".equalsIgnoreCase(publishStatus) ? command : "",
                "controlHistorySize", snapshot.controlHistory.size()
        ));
        persistenceService.save(snapshot);
    }

    /**
     * 工作流异步执行的核心方法，由线程池调用。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>补全请求参数（userId、conversationId 等），确保持久化完整；</li>
     *   <li>初始化追踪和事件发布；</li>
     *   <li>召回记忆并裁剪上下文；</li>
     *   <li>根据路由决策分发到对应引擎（reject / react / rag / workflow）；</li>
     *   <li>异常时标记为 FAILED 并发布失败事件。</li>
     * </ol>
     *
     * @param workflowId    工作流唯一标识
     * @param request       原始请求
     * @param routeDecision 路由决策结果
     */
    private void runAsync(String workflowId, WorkflowRunRequest request, HybridRouteDecision routeDecision) {
        WorkflowRunSnapshot snapshot = persistenceService.find(workflowId);
        if (snapshot == null) {
            log.error("Workflow snapshot disappeared before execution | workflowId={}", workflowId);
            return;
        }
        // 补全缺失字段，使用默认值兜底，确保后续逻辑不会因 null 而中断
        String userId = defaultString(request.userId, "user-001");
        String researchGroupId = defaultString(request.researchGroupId, "replace-me");
        String conversationId = defaultString(request.conversationId, metadataService.buildDefaultConversationId(userId));
        String message = defaultString(request.message, "");
        snapshot.metadata = metadataService.mergeMetadata(snapshot.metadata, Map.of("conversationId", conversationId));
        persistenceService.save(snapshot);

        // 初始化追踪链路，后续所有 step 都会挂在这条链路下
        traceService.startWorkflow(workflowId);
        snapshotHydrator.clearTransientRunData(workflowId);
        eventBus.publish(workflowId, "ROUTER_DONE", "router", "ExecutionDecisionService", null,
                "SUCCESS", routeDecision.reason, Map.of("routeDecision", routeDecision));

        try {
            // Safety rejection and clarification never recall private memory or call retrieval.
            if ("clarification_needed".equals(routeDecision.taskType)) {
                completeClarificationRun(workflowId, snapshot);
                return;
            }
            if ("reject".equals(routeDecision.taskType)) {
                completeRejectedRun(workflowId, snapshot);
                return;
            }
            // 第一步：召回记忆（对话历史、语义记忆等），并裁剪上下文到 token 预算内
            MemoryRecallRecord recall = recallMemory(workflowId, userId, researchGroupId, conversationId,
                    message, routeDecision, snapshot);

            // --- Python runtime dispatch via gRPC ---
            RuntimeTarget runtimeTarget = runtimeSelector.select(request, routeDecision);
            if (runtimeTarget == RuntimeTarget.PYTHON) {
                try {
                    pythonDispatchService.dispatchInitial(
                            workflowId, request, routeDecision, recall, snapshot.contextEnvelope, snapshot);
                    return; // gRPC stream will deliver events and terminal result
                } catch (Exception e) {
                    throw new RuntimeException("Failed to dispatch to Python runtime via gRPC.", e);
                }
            }
            // --- End Python dispatch ---

            // Java runtime: publish WORKFLOW_STARTED here (Python path publishes its own)
            eventBus.publish(workflowId, "WORKFLOW_STARTED", "workflow", "RUNNING",
                    routeDecision.engine + " run started");

            // 第二步：根据路由决策分发到对应引擎（Phase 1：只有 rag 和 reject 留 Java，其余走 Python）
            if ("rag".equals(routeDecision.engine)) {
                runRag(workflowId, userId, researchGroupId, conversationId, message, snapshot, routeDecision, recall);
            } else {
                throw new IllegalStateException(
                        "Engine '" + routeDecision.engine + "' should have been dispatched to Python runtime. "
                                + "Check ExecutionRuntimeSelector.select() logic.");
            }
        } catch (Exception e) {
            // 兜底异常处理：标记失败、发布事件、持久化，确保快照不会卡在 RUNNING 状态
            log.error("Workflow run failed | workflowId={}, error={}", workflowId, e.getMessage(), e);
            snapshot.status = "FAILED";
            snapshot.errorMessage = e.getMessage();
            snapshot.completedAt = Instant.now();
            traceService.finishWorkflow(workflowId, "FAILED", e.getMessage());
            eventBus.publish(workflowId, "WORKFLOW_FAILED", "workflow", "FAILED", e.getMessage());
            persistenceService.save(snapshot);
        } finally {
            // 无论成功失败，都填充动态数据（事件、追踪等），保证快照信息完整
            snapshotHydrator.hydrate(snapshot);
        }
    }

    /**
     * RAG 引擎执行路径：基于检索增强生成的文献问答。
     *
     * <p>通过 {@link QuickQaExecutionService} 执行已路由的闲聊或文献检索，
     * 适用于简单的事实性问题，不需要复杂的多步推理。</p>
     *
     * @param workflowId       工作流 ID
     * @param userId           用户 ID
     * @param researchGroupId  研究组 ID
     * @param conversationId   对话 ID
     * @param message          用户原始消息
     * @param snapshot         当前快照（会被修改）
     * @param routeDecision    路由决策
     * @param recall           记忆召回结果
     */
    private void runRag(String workflowId, String userId, String researchGroupId,
                        String conversationId, String message, WorkflowRunSnapshot snapshot,
                        HybridRouteDecision routeDecision, MemoryRecallRecord recall) {
        long start = System.currentTimeMillis();
        boolean chitchat = routeDecision != null && "chitchat".equalsIgnoreCase(routeDecision.taskType);
        String toolName = chitchat ? "GeneralChatAssistant" : "LiteratureDialogueService";
        String agentStartSummary = chitchat
                ? "General chat generation started"
                : "RAG retrieval and answer generation started";
        String toolStartSummary = chitchat
                ? "Generating social chat response"
                : "Retrieving literature context";
        String toolDoneSummary = chitchat
                ? "General chat generation completed"
                : "Literature retrieval completed";
        String doneSummary = chitchat ? "General chat completed" : "RAG engine completed";
        snapshot.runtimeTarget = "java";
        snapshot.runtimeProvider = "java";
        eventBus.publish(workflowId, "PIPELINE_STARTED", "quick_qa", "QuickQaPipeline", null,
                "RUNNING", agentStartSummary, Map.of());
        eventBus.publish(workflowId, "TOOL_STARTED", "tool", "QuickQaPipeline", toolName,
                "RUNNING", toolStartSummary,
                Map.of("query", message,
                        "taskType", defaultString(routeDecision == null ? null : routeDecision.taskType, "paper_qa")));

        String answer = quickQaExecutionService.execute(workflowId, userId, researchGroupId, conversationId,
                message, recall, chitchat, snapshot, routeDecision);
        long latency = System.currentTimeMillis() - start;

        // 发布工具完成事件，附带延迟信息，方便前端展示性能指标
        eventBus.publish(workflowId, "TOOL_DONE", "tool", "QuickQaPipeline", toolName,
                "SUCCESS", toolDoneSummary, Map.of("latencyMs", latency));
        traceService.recordExecutionStep(
                workflowId, AgentTraceStep.StepType.PIPELINE, "Quick QA Pipeline",
                "quick_qa", message, answer, latency);
        eventBus.publish(workflowId, "PIPELINE_DONE", "quick_qa", "QuickQaPipeline", null,
                "SUCCESS", chitchat ? "General chat answer generated" : "RAG answer generated",
                Map.of("answerPreview", preview(answer)));

        snapshot.status = "COMPLETED";
        snapshot.finalAnswer = answer;
        if (!chitchat) {
            postProcessingService.captureDiagnosticsIfNeeded(
                    userId, researchGroupId, message, snapshot, answer);
        }
        snapshot.completedAt = Instant.now();
        traceService.finishWorkflow(workflowId, "COMPLETED", doneSummary);
        eventBus.publish(workflowId, "WORKFLOW_DONE", "workflow", "COMPLETED", doneSummary);
        persistenceService.save(snapshot);
        postProcessingService.queueMemoryWriteback(
                userId, researchGroupId, conversationId, workflowId, message, answer, routeDecision);
    }

    private void completeClarificationRun(String workflowId, WorkflowRunSnapshot snapshot) {
        String answer = "请补充你希望分析的论文、研究主题或具体问题。";
        snapshot.status = "COMPLETED";
        snapshot.finalAnswer = answer;
        snapshot.completedAt = Instant.now();
        snapshot.warnings = List.of("The request needs clarification before retrieval.");
        traceService.recordExecutionStep(
                workflowId, AgentTraceStep.StepType.ROUTER, "Clarification Decision",
                "router", "", answer, 0);
        traceService.finishWorkflow(workflowId, "COMPLETED", "Clarification requested");
        eventBus.publish(workflowId, "WORKFLOW_DONE", "workflow", "COMPLETED", "Clarification requested");
        persistenceService.save(snapshot);
    }

    /**
     * 处理被安全路由拒绝的请求。
     *
     * <p>当 {@link ExecutionDecisionService} 返回 REJECT 时，
     * 直接返回拒绝提示，不进入任何引擎。</p>
     *
     * @param workflowId 工作流 ID
     * @param snapshot   当前快照（会被修改）
     */
    private void completeRejectedRun(String workflowId, WorkflowRunSnapshot snapshot) {
        // 拒绝消息：不属于学术研究范畴，直接返回提示
        String answer = "This request cannot be handled by the research agent. Please ask an academic research question.";
        snapshot.status = "COMPLETED";
        snapshot.finalAnswer = answer;
        snapshot.completedAt = Instant.now();
        snapshot.warnings = List.of("Rejected by safety routing.");
        // 记录一条特殊的 Agent Step，方便追踪和调试
        traceService.recordExecutionStep(
                workflowId, AgentTraceStep.StepType.ROUTER, "Safety Decision",
                "router", "", answer, 0);
        traceService.finishWorkflow(workflowId, "COMPLETED", "Rejected by router");
        eventBus.publish(workflowId, "WORKFLOW_DONE", "workflow", "COMPLETED", "Rejected by router");
        persistenceService.save(snapshot);
    }

    /**
     * 召回与当前对话相关的记忆，并裁剪上下文到 token 预算内。
     *
     * <p>这是每个引擎执行前的必要步骤。召回的记忆包括：</p>
     * <ul>
     *   <li>对话历史（conversation history）</li>
     *   <li>语义记忆（semantic memory，从历史对话中提取的关键知识）</li>
     *   <li>相关研究上下文</li>
     * </ul>
     * <p>召回后通过 {@link ContextCompilerService} 编译，确保总 token 量不超过预算。</p>
     *
     * @param workflowId       工作流 ID
     * @param userId           用户 ID
     * @param researchGroupId  研究组 ID
     * @param conversationId   对话 ID
     * @param message          用户当前消息（用于语义匹配）
     * @param routeDecision    路由决策（用于确定引擎和 profile）
     * @param snapshot         当前快照（会被修改，记录召回结果和预算信息）
     * @return 记忆召回记录
     */
    private MemoryRecallRecord recallMemory(String workflowId, String userId, String researchGroupId,
                                            String conversationId, String message,
                                            HybridRouteDecision routeDecision,
                                            WorkflowRunSnapshot snapshot) {
        eventBus.publish(workflowId, "MEMORY_RECALL_STARTED", "memory", "MemoryRecallService", null,
                "RUNNING", "Memory recall started", Map.of("conversationId", conversationId));
        // 召回记忆：综合对话历史、语义记忆、研究上下文
        MemoryRecallRecord recall = memoryRecallService.recall(
                userId, researchGroupId, conversationId, message, routeDecision);
        snapshot.memoryRecall = recall;
        eventBus.publish(workflowId, "CONTEXT_BUILD_STARTED", "context", "ContextCompiler", null,
                "RUNNING", "Context compilation started", Map.of());
        ContextCompileRequest compileRequest = new ContextCompileRequest();
        compileRequest.workflowId = workflowId;
        compileRequest.strategy = snapshot.executionDecision == null
                ? routeDecision.engine : snapshot.executionDecision.strategy.name();
        compileRequest.systemPolicy = "Use private research evidence faithfully. Distinguish facts, inferences and unknowns.";
        compileRequest.userQuery = message;
        compileRequest.memoryRecall = recall;
        ContextEnvelope contextEnvelope = contextCompilerService.compile(compileRequest);
        snapshot.contextEnvelope = contextEnvelope;
        snapshot.contextStats = contextEnvelope.stats;
        recall.compactPromptContext = contextEnvelope.promptContext;
        snapshot.contextBudget = toLegacyContextBudget(routeDecision, contextEnvelope);
        if (!contextEnvelope.compressionRecords.isEmpty()) {
            eventBus.publish(workflowId, "CONTEXT_COMPRESSED", "context", "ContextCompiler", null,
                    "SUCCESS", "Context compression completed", Map.of(
                            "beforeTokens", contextEnvelope.stats.originalTokens,
                            "afterTokens", contextEnvelope.stats.finalTokens,
                            "compressionRatio", contextEnvelope.stats.compressionRatio,
                            "records", contextEnvelope.compressionRecords.size()));
        }
        eventBus.publish(workflowId, "CONTEXT_READY", "context", "ContextCompiler", null,
                "SUCCESS", "Context is within the execution budget", Map.of(
                        "contextId", contextEnvelope.contextId,
                        "promptTokens", contextEnvelope.stats.finalTokens,
                        "maxPromptTokens", contextEnvelope.budget.maxPromptTokens,
                        "fingerprint", contextEnvelope.fingerprint));
        eventBus.publish(workflowId, "MEMORY_RECALL_DONE", "memory", "MemoryRecallService", null,
                "SUCCESS", "Memory recall completed",
                Map.of("diagnostics", recall.diagnostics,
                        "semanticMemoryCount", recall.semanticMemories == null ? 0 : recall.semanticMemories.size(),
                        "promptTokens", contextEnvelope.stats.finalTokens,
                        "truncated", !contextEnvelope.compressionRecords.isEmpty()));
        persistenceService.save(snapshot);
        return recall;
    }

    private ContextBudgetSnapshot toLegacyContextBudget(
            HybridRouteDecision routeDecision, ContextEnvelope envelope) {
        ContextBudgetSnapshot legacy = new ContextBudgetSnapshot();
        legacy.engine = routeDecision.engine;
        legacy.profile = routeDecision.profile;
        legacy.maxPromptChars = envelope.budget.maxPromptTokens * 4;
        legacy.totalPromptChars = envelope.promptContext == null ? 0 : envelope.promptContext.length();
        legacy.promptContext = envelope.promptContext;
        legacy.truncated = !envelope.compressionRecords.isEmpty();
        legacy.truncatedSections = envelope.stats.droppedSegments;
        legacy.diagnostics = envelope.stats.warnings;
        return legacy;
    }

    public void resumeRun(String workflowId, String resumeMode) {
        pythonDispatchService.resume(workflowId, resumeMode);
    }

    /**
     * 生成默认的对话 ID。
     *
     * <p>当请求中未指定 conversationId 时，使用 "userId:default" 作为默认值。</p>
     *
     * @param userId 用户 ID
     * @return 默认对话 ID
     */
    /**
     * 空安全的字符串默认值工具方法。
     *
     * @param value    待检查的字符串
     * @param fallback 值为 null 或空白时返回的默认值
     * @return 原始值或默认值
     */
    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 文本预览截断：超过 360 字符时截断并添加省略号。
     *
     * <p>用于 SSE 事件和日志中的文本预览，避免传输过长内容。</p>
     *
     * @param text 原始文本
     * @return 截断后的预览文本
     */
    private String preview(String text) {
        if (text == null) return "";
        return text.length() > 360 ? text.substring(0, 360) + "..." : text;
    }
}
