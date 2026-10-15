package com.team.rag.agent.tool;

import com.team.rag.service.DocumentLibraryCatalogService;
import org.springframework.stereotype.Component;

/**
 * 图书馆摘要工具 —— 返回当前研究组上传文档库的紧凑摘要。
 *
 * <p>职责：为 ReAct Agent 提供文档库的概览信息，包括文档数量、文件名列表、
 * 处理状态等，帮助 Agent 了解当前可用的知识库范围。
 *
 * <p>设计思想：
 * <ul>
 *   <li>与 {@link ListPapersTool} 功能类似，但输出更精简，默认 limit=20</li>
 *   <li>工具类型为 RETRIEVAL，属于检索类工具</li>
 *   <li>优先级为 6，略高于 ListPapersTool（5），因为摘要通常是更高效的概览方式</li>
 *   <li>researchGroupId 支持显式指定或从上下文自动获取</li>
 * </ul>
 *
 * <p>在系统中的位置：MCPToolRegistry 注册 -> ReAct Agent 在需要了解文档库概况时调用。
 */
@Component
public class LibrarySummaryTool implements ScholarAgentTool<LibrarySummaryTool.Input, LibrarySummaryTool.Output> {

    /** 文档库目录服务，负责查询文档清单 */
    private final DocumentLibraryCatalogService catalogService;

    public LibrarySummaryTool(DocumentLibraryCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * 工具名称，用于 MCPToolRegistry 中的路由匹配。
     */
    @Override
    public String name() {
        return "get_library_summary";
    }

    /**
     * 工具描述，注入到 LLM 的系统提示中。
     */
    @Override
    public String description() {
        return "Return a compact summary of the uploaded document library for the current researchGroupId.";
    }

    /**
     * 输入 Schema。
     * <p>researchGroupId 和 limit 均为可选，未指定时使用默认值。
     */
    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("Library summary input");
        schema.addProperty("researchGroupId", ToolSchema.PropertySchema.string("Research group id. Defaults to the workflow researchGroupId."));
        schema.addProperty("limit", ToolSchema.PropertySchema.integer("Maximum number of documents to include. Default 20."));
        return schema;
    }

    /**
     * 输出 Schema。
     */
    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("Library summary output");
        schema.addProperty("result", ToolSchema.PropertySchema.object("Compact document library summary."));
        return schema;
    }

    /**
     * 执行图书馆摘要查询。
     *
     * @param input   包含 researchGroupId 和 limit 的输入参数
     * @param context 工具执行上下文
     * @return 包含文档库目录结果的 ToolResult
     */
    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        long start = System.currentTimeMillis();
        Input safeInput = input == null ? new Input() : input;
        // researchGroupId 优先使用输入参数，回退到上下文中的值
        String researchGroupId = safeInput.researchGroupId != null && !safeInput.researchGroupId.isBlank()
                ? safeInput.researchGroupId.trim()
                : context != null ? context.getResearchGroupId() : null;

        Output output = new Output();
        // limit 默认 20，includeChunks 为 false（摘要不需要 chunk 详情）
        output.result = catalogService.list(researchGroupId, safeInput.limit == null ? 20 : safeInput.limit, false);
        return ToolResult.success(output, System.currentTimeMillis() - start);
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    /** 工具类型为 RETRIEVAL（检索类） */
    @Override
    public ToolType type() {
        return ToolType.RETRIEVAL;
    }

    /**
     * 工具优先级，值越小优先级越高。
     * <p>设为 6，略高于 ListPapersTool（5），因为摘要通常是更高效的概览方式。
     */
    @Override
    public int priority() {
        return 6;
    }

    /**
     * 工具输入参数。
     */
    public static class Input {
        /** 研究组标识，为 null 时使用上下文中的值 */
        public String researchGroupId;
        /** 最大返回文档数，默认 20 */
        public Integer limit;
    }

    /**
     * 工具输出结果。
     */
    public static class Output {
        /** 文档库目录结果，包含论文列表、总数、摘要和警告信息 */
        public DocumentLibraryCatalogService.CatalogResult result;
    }
}
