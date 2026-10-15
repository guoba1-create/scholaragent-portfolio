package com.team.rag.agent.tool;

import com.team.rag.service.DocumentLibraryCatalogService;
import org.springframework.stereotype.Component;

/**
 * 论文列表工具 —— 列出当前研究组上传的所有论文/文档。
 *
 * <p>职责：为 ReAct Agent 提供文档库的完整清单，支持分页限制和可选的 chunk 索引预览，
 * 回答用户关于"库中有哪些文献"的清单类问题。
 *
 * <p>设计思想：
 * <ul>
 *   <li>与 {@link LibrarySummaryTool} 互补：本工具返回更详细的论文列表，
 *       LibrarySummaryTool 返回更精简的摘要</li>
 *   <li>工具类型为 RETRIEVAL，属于检索类工具</li>
 *   <li>优先级为 5，低于 LibrarySummaryTool（6），默认优先使用摘要工具</li>
 *   <li>支持 includeChunks 参数，可选择性返回 chunk 索引预览</li>
 * </ul>
 *
 * <p>在系统中的位置：MCPToolRegistry 注册 -> ReAct Agent 在用户询问文献清单时调用。
 */
@Component
public class ListPapersTool implements ScholarAgentTool<ListPapersTool.Input, ListPapersTool.Output> {

    /** 文档库目录服务，负责查询文档清单 */
    private final DocumentLibraryCatalogService catalogService;

    public ListPapersTool(DocumentLibraryCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * 工具名称，用于 MCPToolRegistry 中的路由匹配。
     */
    @Override
    public String name() {
        return "list_papers";
    }

    /**
     * 工具描述，注入到 LLM 的系统提示中。
     * <p>描述中包含典型的用户问题示例，帮助 LLM 准确匹配。
     */
    @Override
    public String description() {
        return "List uploaded papers/documents in the current researchGroupId. Use this for questions like '当前库中有哪些文献' or 'what papers are in my library'.";
    }

    /**
     * 输入 Schema。
     */
    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("List papers input");
        schema.addProperty("researchGroupId", ToolSchema.PropertySchema.string("Research group id. Defaults to the workflow researchGroupId."));
        schema.addProperty("limit", ToolSchema.PropertySchema.integer("Maximum number of papers to return. Default 30, max 100."));
        schema.addProperty("includeChunks", ToolSchema.PropertySchema.booleanType("Whether to include a short chunk index preview."));
        return schema;
    }

    /**
     * 输出 Schema。
     */
    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("List papers output");
        schema.addProperty("result", ToolSchema.PropertySchema.object("Document catalog result with papers, counts, warnings, and summary."));
        return schema;
    }

    /**
     * 执行论文列表查询。
     *
     * @param input   包含 researchGroupId、limit、includeChunks 的输入参数
     * @param context 工具执行上下文
     * @return 包含文档库目录结果的 ToolResult
     */
    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        long start = System.currentTimeMillis();
        Input safeInput = input == null ? new Input() : input;
        // researchGroupId 优先使用输入参数，回退到上下文中的值
        String researchGroupId = firstNonBlank(safeInput.researchGroupId, context != null ? context.getResearchGroupId() : null);

        Output output = new Output();
        output.result = catalogService.list(researchGroupId, safeInput.limit, Boolean.TRUE.equals(safeInput.includeChunks));
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
     * <p>设为 5，低于 LibrarySummaryTool（6），默认优先使用摘要工具。
     */
    @Override
    public int priority() {
        return 5;
    }

    /**
     * 返回第一个非空非空白的字符串，否则返回 null。
     */
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? null : second.trim();
    }

    /**
     * 工具输入参数。
     */
    public static class Input {
        /** 研究组标识，为 null 时使用上下文中的值 */
        public String researchGroupId;
        /** 最大返回论文数，默认 30，最大 100 */
        public Integer limit;
        /** 是否包含 chunk 索引预览 */
        public Boolean includeChunks;
    }

    /**
     * 工具输出结果。
     */
    public static class Output {
        /** 文档库目录结果，包含论文列表、总数、摘要和警告信息 */
        public DocumentLibraryCatalogService.CatalogResult result;
    }
}
