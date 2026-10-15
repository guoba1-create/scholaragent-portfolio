package com.team.rag.agent.tool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具列表工具 —— 列出当前 Agent 运行时可用的所有工具名称和描述。
 *
 * <p>职责：为 ReAct Agent 提供自省能力，让它能够查询当前可用的工具列表，
 * 从而在推理过程中准确选择正确的工具名称，避免幻觉工具名。
 *
 * <p>设计思想：
 * <ul>
 *   <li><b>自省元工具</b>：这是一种"工具的工具"，不执行具体的检索或计算，
 *       而是返回工具注册表的元信息</li>
 *   <li><b>减少工具名幻觉</b>：LLM 在不知道确切工具名时容易编造名称，
 *       本工具让 LLM 可以先查询再调用，大幅降低幻觉率</li>
 *   <li><b>支持过滤</b>：通过可选的 filter 参数支持按名称或描述模糊过滤</li>
 *   <li><b>ObjectProvider 延迟注入</b>：使用 ObjectProvider 避免与 MCPToolRegistry 的循环依赖</li>
 * </ul>
 *
 * <p>在系统中的位置：MCPToolRegistry 注册 -> ReAct Agent 在不确定工具名时调用。
 */
@Component
public class ListToolsTool implements ScholarAgentTool<ListToolsTool.Input, ListToolsTool.Output> {

    /**
     * MCPToolRegistry 的延迟提供者。
     * <p>使用 ObjectProvider 而非直接注入，避免与 MCPToolRegistry 的循环依赖
     * （MCPToolRegistry 注册本工具时，本工具又依赖 MCPToolRegistry）。
     */
    private final ObjectProvider<MCPToolRegistry> registryProvider;

    public ListToolsTool(ObjectProvider<MCPToolRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    /**
     * 工具名称，用于 MCPToolRegistry 中的路由匹配。
     */
    @Override
    public String name() {
        return "list_tools";
    }

    /**
     * 工具描述，注入到 LLM 的系统提示中。
     */
    @Override
    public String description() {
        return "List the exact tool names currently available to the Agent runtime.";
    }

    /**
     * 输入 Schema。
     * <p>仅有一个可选的 filter 参数。
     */
    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("List tools input");
        schema.addProperty("filter", ToolSchema.PropertySchema.string("Optional case-insensitive name or description filter."));
        return schema;
    }

    /**
     * 输出 Schema。
     */
    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("List tools output");
        schema.addProperty("tools", ToolSchema.PropertySchema.array("Available tools with exact names and descriptions."));
        schema.addProperty("toolCount", ToolSchema.PropertySchema.integer("Number of returned tools."));
        return schema;
    }

    /**
     * 执行工具列表查询。
     *
     * <p>核心逻辑：
     * <ol>
     *   <li>通过 ObjectProvider 获取 MCPToolRegistry 实例</li>
     *   <li>获取所有可用工具</li>
     *   <li>根据 filter 参数进行模糊过滤（匹配名称或描述）</li>
     *   <li>构建 ToolInfo 列表和汇总摘要</li>
     * </ol>
     *
     * @param input   包含可选 filter 的输入参数
     * @param context 工具执行上下文
     * @return 包含工具列表和数量的 ToolResult
     */
    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        long start = System.currentTimeMillis();
        MCPToolRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return ToolResult.failure("REGISTRY_UNAVAILABLE", "MCPToolRegistry is unavailable", System.currentTimeMillis() - start);
        }

        // 处理 filter 参数：转小写用于大小写不敏感匹配
        String filter = input == null || input.filter == null ? "" : input.filter.trim().toLowerCase();
        Output output = new Output();
        registry.getAvailableTools().stream()
                // filter 为空时返回所有工具，否则按名称或描述模糊匹配
                .filter(tool -> filter.isBlank()
                        || tool.name().toLowerCase().contains(filter)
                        || tool.description().toLowerCase().contains(filter))
                .forEach(tool -> {
                    ToolInfo info = new ToolInfo();
                    info.name = tool.name();
                    info.type = tool.type().name();
                    info.description = tool.description();
                    output.tools.add(info);
                });
        output.toolCount = output.tools.size();
        // 生成所有工具名称的汇总字符串，方便 LLM 快速扫描
        output.summary = "Available exact tool names: " + String.join(", ",
                output.tools.stream().map(tool -> tool.name).toList());
        return ToolResult.success(output, System.currentTimeMillis() - start);
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    /** 工具类型为 CUSTOM（自定义元工具），区别于 RETRIEVAL、GENERATION 等业务工具 */
    @Override
    public ToolType type() {
        return ToolType.CUSTOM;
    }

    /**
     * 工具优先级，设为 1（最高）。
     * <p>元工具优先级最高，确保 LLM 可以快速查询可用工具。
     */
    @Override
    public int priority() {
        return 1;
    }

    /**
     * 工具输入参数。
     */
    public static class Input {
        /** 可选的过滤关键词，大小写不敏感，匹配工具名称或描述 */
        public String filter;
    }

    /**
     * 工具输出结果。
     */
    public static class Output {
        /** 返回的工具数量 */
        public int toolCount;
        /** 所有工具名称的汇总字符串 */
        public String summary;
        /** 工具信息列表 */
        public List<ToolInfo> tools = new ArrayList<>();
    }

    /**
     * 单个工具的信息。
     */
    public static class ToolInfo {
        /** 工具名称（精确名称，LLM 应直接使用） */
        public String name;
        /** 工具类型（如 RETRIEVAL, CUSTOM 等） */
        public String type;
        /** 工具描述 */
        public String description;
    }
}
