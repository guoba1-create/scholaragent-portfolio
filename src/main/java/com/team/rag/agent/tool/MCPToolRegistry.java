package com.team.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.runtime.WorkflowEventBus;
import com.team.rag.agent.trace.AgentTraceService;
import com.team.rag.agent.trace.AgentTraceStep;
import com.team.rag.monitoring.AgentBusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 工具注册表 —— 系统中所有 Agent 工具的统一注册、查找和执行中心。
 *
 * <p>职责：
 * <ul>
 *   <li><b>工具注册</b>：在构造时自动注册所有 Spring 管理的工具 Bean，
 *       同时支持运行时动态注册</li>
 *   <li><b>工具查找</b>：按名称精确查找工具，或获取所有可用工具列表</li>
 *   <li><b>工具执行</b>：统一的工具执行入口，负责输入类型转换、Trace 记录、
 *       事件发布和异常处理</li>
 *   <li><b>Schema 导出</b>：生成工具描述文本和 JSON Schema，供 LLM 系统提示使用</li>
 * </ul>
 *
 * <p>设计思想：
 * <ul>
 *   <li><b>统一入口</b>：所有工具调用都经过 executeTool()，保证 Trace、Event、
 *       错误处理的一致性</li>
 *   <li><b>可观测性</b>：每次工具执行都记录 TraceStep 和 WorkflowEvent，
 *       支持前端 Trace 面板和 SSE 事件流</li>
 *   <li><b>类型安全</b>：通过 ObjectMapper.convertValue 自动将 Map 输入
 *       转换为工具期望的类型</li>
 *   <li><b>并发安全</b>：使用 ConcurrentHashMap 存储工具映射，支持运行时动态注册</li>
 *   <li><b>MCP 兼容</b>：工具 Schema 遵循 MCP（Model Context Protocol）规范，
 *       便于未来对接外部 MCP 服务器</li>
 * </ul>
 *
 * <p>在系统中的位置：Spring 容器启动时自动注入所有 ScholarAgentTool Bean 并注册；
 * Python MCP runtime、Workflow 节点和内部诊断组件通过本注册表查找和执行工具。
 */
@Component
public class MCPToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(MCPToolRegistry.class);

    /**
     * 工具映射表：工具名 -> 工具实例。
     * <p>使用 ConcurrentHashMap 保证并发安全，支持运行时动态注册。
     */
    private final Map<String, ScholarAgentTool<?, ?>> tools = new ConcurrentHashMap<>();

    /** Trace 服务，记录每次工具执行的轨迹 */
    private final AgentTraceService traceService;

    /** JSON 序列化器，用于输入/输出的序列化和类型转换 */
    private final ObjectMapper objectMapper;

    /** 工作流事件总线，发布工具执行事件供前端 SSE 监听 */
    private final WorkflowEventBus eventBus;
    private final AgentBusinessMetrics businessMetrics;

    /**
     * 构造函数 —— 自动注册所有注入的工具 Bean。
     * <p>Spring 容器启动时，将所有 ScholarAgentTool 实现类注入此处并统一注册。
     */
    public MCPToolRegistry(AgentTraceService traceService,
                           ObjectMapper objectMapper,
                           WorkflowEventBus eventBus,
                           AgentBusinessMetrics businessMetrics,
                           EvidenceRetrievalTool evidenceRetrievalTool,
                           QueryRewriteTool queryRewriteTool,
                           EvidenceQualityTool evidenceQualityTool,
                           DocumentReadTool documentReadTool,
                           ListPapersTool listPapersTool,
                           LibrarySummaryTool librarySummaryTool,
                           LibraryDiagnosticsTool libraryDiagnosticsTool,
                           ListToolsTool listToolsTool,
                           SemanticScholarTool semanticScholarTool,
                           WebSearchTool webSearchTool,
                           MemoryRecallTool memoryRecallTool) {
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.eventBus = eventBus;
        this.businessMetrics = businessMetrics;

        // 注册所有内置工具
        register(evidenceRetrievalTool);
        register(queryRewriteTool);
        register(evidenceQualityTool);
        register(documentReadTool);
        register(listPapersTool);
        register(librarySummaryTool);
        register(libraryDiagnosticsTool);
        register(listToolsTool);
        register(semanticScholarTool);
        register(webSearchTool);
        register(memoryRecallTool);

        log.info("MCPToolRegistry initialized | tools={}", tools.size());
    }

    /**
     * 注册一个工具到注册表。
     *
     * @param tool 待注册的工具实例，name 不能为空
     */
    public void register(ScholarAgentTool<?, ?> tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            log.warn("Skip registering an empty tool");
            return;
        }
        tools.put(tool.name(), tool);
        log.debug("Registered tool | name={}, type={}", tool.name(), tool.type());
    }

    /**
     * 按名称查找工具。
     */
    public ScholarAgentTool<?, ?> getTool(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册的工具（包括不可用的）。
     */
    public List<ScholarAgentTool<?, ?>> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 获取所有可用的工具，按优先级排序。
     * <p>过滤掉 isAvailable() 返回 false 的工具。
     */
    public List<ScholarAgentTool<?, ?>> getAvailableTools() {
        return tools.values().stream()
                .filter(ScholarAgentTool::isAvailable)
                .sorted(Comparator.comparingInt(ScholarAgentTool::priority))
                .collect(Collectors.toList());
    }

    /**
     * 执行工具 —— 统一的工具执行入口。
     *
     * <p>完整流程：
     * <ol>
     *   <li>查找工具并检查可用性</li>
     *   <li>输入类型转换（Map -> 工具期望的类型）</li>
     *   <li>创建 TraceStep 记录执行开始</li>
     *   <li>发布 TOOL_STARTED 事件</li>
     *   <li>执行工具</li>
     *   <li>记录 TraceStep 完成/失败</li>
     *   <li>发布 TOOL_DONE 事件</li>
     *   <li>返回 ToolResult</li>
     * </ol>
     *
     * @param toolName  工具名称
     * @param input     工具输入（通常为 Map 或工具特定类型）
     * @param context   工具执行上下文
     * @return 工具执行结果
     */
    @SuppressWarnings("unchecked")
    public ToolResult<?> executeTool(String toolName, Object input, ToolContext context) {
        ScholarAgentTool<Object, Object> tool = (ScholarAgentTool<Object, Object>) tools.get(toolName);
        if (tool == null) {
            log.error("Tool does not exist | toolName={}", toolName);
            businessMetrics.toolExecution(toolName, "not_found", 0);
            return ToolResult.failure("TOOL_NOT_FOUND", "Tool does not exist: " + toolName);
        }

        if (!tool.isAvailable()) {
            log.warn("Tool is unavailable | toolName={}", toolName);
            businessMetrics.toolExecution(toolName, "unavailable", 0);
            return ToolResult.failure("TOOL_UNAVAILABLE", "Tool is unavailable: " + toolName);
        }

        ToolContext safeContext = context == null ? new ToolContext() : context;
        log.info("Execute tool | toolName={}, workflowId={}", toolName, safeContext.getWorkflowId());

        long startTime = System.currentTimeMillis();
        AgentTraceStep traceStep = null;
        // 将输入转换为工具期望的类型（如 Map -> Input 类）
        Object convertedInput = convertInput(tool, input);

        // 如果 Trace 启用，创建 TraceStep 记录
        if (safeContext.isTraceEnabled() && safeContext.getWorkflowId() != null) {
            traceStep = AgentTraceStep.toolStep(safeContext.getWorkflowId(), toolName);
            traceStep.setFullInput(serializeInput(convertedInput));
        }
        // 发布 TOOL_STARTED 事件
        publishToolEvent(safeContext, "TOOL_STARTED", toolName, "RUNNING", "Tool execution started",
                eventPayload("input", preview(serializeInput(convertedInput)), safeContext));

        try {
            // 执行工具
            ToolResult<Object> result = tool.execute(convertedInput, safeContext);
            long latencyMs = System.currentTimeMillis() - startTime;
            result.setLatencyMs(latencyMs);

            // 记录 Trace 完成
            if (traceStep != null && safeContext.getWorkflowId() != null) {
                traceStep.complete(serializeOutput(result.getData()), latencyMs);
                traceService.recordToolStep(safeContext.getWorkflowId(), toolName,
                        serializeInput(convertedInput), serializeOutput(result.getData()), latencyMs);
            }
            // 发布 TOOL_DONE 事件
            publishToolEvent(safeContext, "TOOL_DONE", toolName, result.isSuccess() ? "SUCCESS" : "FAILED",
                    result.isSuccess() ? toolDoneSummary(toolName, result.getData()) : result.getErrorMessage(),
                    eventPayload(latencyMs, preview(serializeOutput(result.getData())), result.getErrorCode(), safeContext));

            log.info("Tool execution completed | toolName={}, success={}, latency={}ms",
                    toolName, result.isSuccess(), latencyMs);
            businessMetrics.toolExecution(toolName, result.isSuccess() ? "success" : "failed", latencyMs);
            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.error("Tool execution failed | toolName={}, error={}", toolName, e.getMessage(), e);

            // 记录 Trace 失败
            if (traceStep != null && safeContext.getWorkflowId() != null) {
                traceStep.fail(e.getMessage(), latencyMs);
                traceService.recordFailure(safeContext.getWorkflowId(), AgentTraceStep.StepType.TOOL,
                        toolName, serializeInput(convertedInput), e.getMessage(), latencyMs);
            }
            // 发布 TOOL_DONE(FAILED) 事件
            publishToolEvent(safeContext, "TOOL_DONE", toolName, "FAILED", e.getMessage(),
                    errorPayload(latencyMs, e.getMessage(), safeContext));

            businessMetrics.toolExecution(toolName, "exception", latencyMs);

            return ToolResult.failure("TOOL_EXECUTION_ERROR", e.getMessage(), latencyMs);
        }
    }

    /**
     * 获取所有可用工具的文本描述。
     * <p>用于注入 LLM 的系统提示。
     */
    public String getToolsDescription() {
        return getToolsDescription(null);
    }

    /**
     * 获取指定工具子集的文本描述。
     *
     * @param allowedToolNames 允许的工具名称集合，为 null 时返回所有工具
     */
    public String getToolsDescription(Collection<String> allowedToolNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools. Use exact tool names only; never invent a tool name.\n\n");

        for (ScholarAgentTool<?, ?> tool : getAvailableTools()) {
            // 过滤不在允许列表中的工具
            if (allowedToolNames != null && !allowedToolNames.isEmpty() && !allowedToolNames.contains(tool.name())) {
                continue;
            }
            sb.append("### ").append(tool.name()).append("\n");
            sb.append("Description: ").append(tool.description()).append("\n");
            sb.append("Type: ").append(tool.type().name()).append("\n");

            // 输出输入参数 Schema
            ToolSchema inputSchema = tool.inputSchema();
            if (inputSchema != null && inputSchema.getProperties() != null && !inputSchema.getProperties().isEmpty()) {
                sb.append("Input parameters:\n");
                for (Map.Entry<String, ToolSchema.PropertySchema> entry : inputSchema.getProperties().entrySet()) {
                    sb.append("  - ").append(entry.getKey()).append(": ")
                            .append(entry.getValue().getDescription())
                            .append(" (").append(entry.getValue().getType()).append(")\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取所有工具的 JSON Schema。
     * <p>用于 MCP 协议兼容的工具发现。
     */
    public Map<String, Object> getToolsSchemaJson() {
        Map<String, Object> schema = new LinkedHashMap<>();
        for (ScholarAgentTool<?, ?> tool : getAvailableTools()) {
            Map<String, Object> toolSchema = new LinkedHashMap<>();
            toolSchema.put("name", tool.name());
            toolSchema.put("description", tool.description());
            toolSchema.put("type", tool.type().name());
            toolSchema.put("inputSchema", tool.inputSchema());
            toolSchema.put("outputSchema", tool.outputSchema());
            schema.put(tool.name(), toolSchema);
        }
        return schema;
    }

    /**
     * 获取所有可用工具的名称列表。
     */
    public List<String> getAvailableToolNames() {
        return getAvailableTools().stream()
                .map(ScholarAgentTool::name)
                .toList();
    }

    /**
     * 检查指定工具是否已注册。
     */
    public boolean hasTool(String toolName) {
        return tools.containsKey(toolName);
    }

    /**
     * 获取已注册工具总数。
     */
    public int getToolCount() {
        return tools.size();
    }

    /**
     * 将输入对象序列化为 JSON 字符串。
     */
    private String serializeInput(Object input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return input != null ? input.toString() : "null";
        }
    }

    /**
     * 将输出对象序列化为 JSON 字符串。
     */
    private String serializeOutput(Object output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            return output != null ? output.toString() : "null";
        }
    }

    /**
     * 将输入转换为工具期望的类型。
     * <p>当 LLM 传入 Map 格式的输入时，通过 ObjectMapper.convertValue
     * 自动转换为工具的 Input 类。
     */
    private Object convertInput(ScholarAgentTool<Object, Object> tool, Object input) {
        Class<Object> inputType = tool.inputType();
        if (inputType == null || input == null || inputType.isInstance(input)) {
            return input; // 已经是正确类型，无需转换
        }
        return objectMapper.convertValue(input, inputType);
    }

    /**
     * 发布工具执行事件到 WorkflowEventBus。
     */
    private void publishToolEvent(ToolContext context, String type, String toolName,
                                  String status, String summary, Map<String, Object> payload) {
        if (context == null || context.getWorkflowId() == null) {
            return;
        }
        eventBus.publish(context.getWorkflowId(), type, "tool", context.getAgentName(), toolName,
                status, summary, payload);
    }

    /**
     * 构建简单的事件负载。
     */
    private Map<String, Object> eventPayload(String key, Object value, ToolContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        payload.put("agentName", context.getAgentName() == null ? "" : context.getAgentName());
        return payload;
    }

    /**
     * 构建工具完成事件的负载。
     */
    private Map<String, Object> eventPayload(long latencyMs, String output, String errorCode, ToolContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("latencyMs", latencyMs);
        payload.put("output", output);
        payload.put("errorCode", errorCode == null ? "" : errorCode);
        payload.put("agentName", context.getAgentName() == null ? "" : context.getAgentName());
        return payload;
    }

    /**
     * 构建工具失败事件的负载。
     */
    private Map<String, Object> errorPayload(long latencyMs, String error, ToolContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("latencyMs", latencyMs);
        payload.put("error", error == null ? "" : error);
        payload.put("agentName", context.getAgentName() == null ? "" : context.getAgentName());
        return payload;
    }

    /**
     * 生成工具执行完成的一句话摘要。
     * <p>根据工具类型生成人类可读的摘要，用于事件和日志。
     */
    private String toolDoneSummary(String toolName, Object output) {
        if ("list_papers".equals(toolName) && output instanceof ListPapersTool.Output listOutput && listOutput.result != null) {
            return listOutput.result.total <= 0
                    ? "No documents found for " + listOutput.result.researchGroupId
                    : "Found " + listOutput.result.total + " document(s)";
        }
        if ("get_library_summary".equals(toolName)
                && output instanceof LibrarySummaryTool.Output summaryOutput
                && summaryOutput.result != null) {
            return summaryOutput.result.total <= 0
                    ? "Library summary is empty for " + summaryOutput.result.researchGroupId
                    : "Summarized " + summaryOutput.result.total + " document(s)";
        }
        if ("retrieve_evidence".equals(toolName) && output instanceof EvidenceRetrievalTool.Output evidenceOutput) {
            return "Retrieved " + evidenceOutput.totalResults + " evidence span(s)";
        }
        if ("read_document".equals(toolName) && output instanceof DocumentReadTool.Output readOutput && readOutput.result != null) {
            return readOutput.result.chunkIndex == null
                    ? "Read pages " + readOutput.result.pageStart + "-" + readOutput.result.pageEnd
                    : "Read chunk " + readOutput.result.chunkIndex;
        }
        return "Tool execution completed";
    }

    /**
     * 截断文本到 500 字符，用于事件负载的预览。
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }
}
