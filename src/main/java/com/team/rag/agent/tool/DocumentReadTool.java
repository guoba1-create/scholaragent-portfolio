package com.team.rag.agent.tool;

import com.team.rag.entity.DocumentReadResult;
import com.team.rag.service.DocumentReadService;
import org.springframework.stereotype.Component;

/**
 * 文档读取工具 —— 按 documentId 和页码范围或 chunk 索引读取文档原文。
 *
 * <p>职责：为 ReAct Agent 提供精确的文档原文读取能力，支持两种读取模式：
 * <ul>
 *   <li><b>页码模式</b>：指定 pageStart ~ pageEnd 范围，读取连续页面的文本</li>
 *   <li><b>Chunk 模式</b>：指定 chunkIndex，读取预处理时分割的单个文本块</li>
 * </ul>
 *
 * <p>设计思想：
 * <ul>
 *   <li>与 {@link EvidenceRetrievalTool}（语义检索）互补：检索工具找到相关片段后，
 *       Agent 可通过本工具读取上下文更完整的原文</li>
 *   <li>输入校验严格：documentId 必填，pageStart/pageEnd 和 chunkIndex 二选一</li>
 *   <li>工具类型为 RETRIEVAL，属于检索类工具，优先级适中</li>
 * </ul>
 *
 * <p>在系统中的位置：MCPToolRegistry 注册 -> ReAct Agent 在需要精确阅读原文时调用。
 */
@Component
public class DocumentReadTool implements ScholarAgentTool<DocumentReadTool.Input, DocumentReadTool.Output> {

    /** 文档读取服务，负责实际的文档文本读取 */
    private final DocumentReadService documentReadService;

    public DocumentReadTool(DocumentReadService documentReadService) {
        this.documentReadService = documentReadService;
    }

    /**
     * 工具名称，用于 MCPToolRegistry 中的路由匹配。
     */
    @Override
    public String name() {
        return "read_document";
    }

    /**
     * 工具描述，注入到 LLM 的系统提示中，帮助 LLM 决定何时使用此工具。
     */
    @Override
    public String description() {
        return "Read uploaded document text by documentId and page range or chunk index.";
    }

    /**
     * 输入 Schema，定义工具接受的参数。
     * <p>documentId 为必填，pageStart/pageEnd 和 chunkIndex 为可选（二选一）。
     */
    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("Read document input");
        schema.addProperty("documentId", ToolSchema.PropertySchema.string("Uploaded document id"));
        schema.addProperty("pageStart", ToolSchema.PropertySchema.integer("First page number, 1-based"));
        schema.addProperty("pageEnd", ToolSchema.PropertySchema.integer("Last page number, 1-based"));
        schema.addProperty("chunkIndex", ToolSchema.PropertySchema.integer("Chunk index to read"));
        schema.required("documentId");
        return schema;
    }

    /**
     * 输出 Schema，定义工具返回的结构。
     */
    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("Read document output");
        schema.addProperty("result", ToolSchema.PropertySchema.object("Document read result"));
        return schema;
    }

    /**
     * 执行文档读取。
     *
     * <p>核心逻辑：
     * <ol>
     *   <li>校验 documentId 是否有效</li>
     *   <li>根据是否指定 chunkIndex 决定读取模式</li>
     *   <li>调用 DocumentReadService 读取文档</li>
     *   <li>检查读取是否成功并返回结果</li>
     * </ol>
     *
     * @param input   包含 documentId、pageStart/pageEnd、chunkIndex 的输入参数
     * @param context 工具执行上下文（包含工作流、用户等信息）
     * @return 包含文档读取结果的 ToolResult
     */
    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        long start = System.currentTimeMillis();
        // 校验必填参数
        if (input == null || input.documentId == null || input.documentId.isBlank()) {
            return ToolResult.failure("INVALID_INPUT", "documentId is required",
                    System.currentTimeMillis() - start);
        }

        // 根据是否指定 chunkIndex 选择读取模式
        DocumentReadResult result = input.chunkIndex == null
                ? documentReadService.readPages(input.documentId, input.pageStart, input.pageEnd)
                : documentReadService.readChunk(input.documentId, input.chunkIndex);
        if (!result.success) {
            return ToolResult.failure("DOCUMENT_READ_FAILED", result.errorMessage,
                    System.currentTimeMillis() - start);
        }

        Output output = new Output();
        output.result = result;
        return ToolResult.success(output, System.currentTimeMillis() - start);
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    /** 工具类型为 RETRIEVAL（检索类），与证据检索、论文列表等工具同属一类 */
    @Override
    public ToolType type() {
        return ToolType.RETRIEVAL;
    }

    /**
     * 工具输入参数。
     */
    public static class Input {
        /** 已上传文档的唯一标识 */
        public String documentId;
        /** 起始页码（1-based），页码模式下使用 */
        public Integer pageStart;
        /** 结束页码（1-based），页码模式下使用 */
        public Integer pageEnd;
        /** Chunk 索引，Chunk 模式下使用；与 pageStart/pageEnd 互斥 */
        public Integer chunkIndex;
    }

    /**
     * 工具输出结果。
     */
    public static class Output {
        /** 文档读取结果，包含文本内容、页码信息和 chunk 引用 */
        public DocumentReadResult result;
    }
}
