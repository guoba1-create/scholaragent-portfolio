package com.team.rag.agent.tool;

import com.team.rag.service.AcademicQueryRewriter;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询改写工具
 *
 * <p>封装学术查询改写能力，用于：
 * <ul>
 *   <li>指代消解</li>
 *   <li>术语扩展</li>
 *   <li>查询优化</li>
 * </ul>
 */
@Component
public class QueryRewriteTool implements ScholarAgentTool<QueryRewriteTool.Input, QueryRewriteTool.Output> {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteTool.class);

    private final AcademicQueryRewriter queryRewriter;

    public QueryRewriteTool(AcademicQueryRewriter queryRewriter) {
        this.queryRewriter = queryRewriter;
    }

    @Override
    public String name() {
        return "query_rewrite";
    }

    @Override
    public String description() {
        return "改写学术查询，进行指代消解和术语扩展";
    }

    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("查询改写输入");
        schema.addProperty("originalQuery", ToolSchema.PropertySchema.string("原始查询"));
        schema.addProperty("context", ToolSchema.PropertySchema.string("上下文信息"));
        schema.required("originalQuery");
        return schema;
    }

    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("查询改写输出");
        schema.addProperty("rewrittenQuery", ToolSchema.PropertySchema.string("改写后的查询"));
        schema.addProperty("changes", ToolSchema.PropertySchema.string("改写说明"));
        return schema;
    }

    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        log.info("执行查询改写 | originalQuery={}", truncate(input.originalQuery, 100));

        long startTime = System.currentTimeMillis();

        try {
            String rewrittenQuery = queryRewriter.rewrite(List.of(), input.originalQuery);

            Output output = new Output();
            output.rewrittenQuery = rewrittenQuery;
            output.changes = describeChanges(input.originalQuery, rewrittenQuery);

            long latencyMs = System.currentTimeMillis() - startTime;
            log.info("查询改写完成 | rewrittenQuery={}, latency={}ms", truncate(rewrittenQuery, 100), latencyMs);

            return ToolResult.success(output, latencyMs);

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.error("查询改写失败 | error={}", e.getMessage(), e);

            // Fallback to original query
            Output output = new Output();
            output.rewrittenQuery = input.originalQuery;
            output.changes = "改写失败，使用原始查询";

            return ToolResult.success(output, latencyMs);
        }
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public ToolType type() {
        return ToolType.ANALYSIS;
    }

    private String describeChanges(String original, String rewritten) {
        if (original.equals(rewritten)) {
            return "无需改写";
        }
        return "已优化查询表述";
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 输入类
     */
    public static class Input {
        public String originalQuery;
        public String context;
    }

    /**
     * 输出类
     */
    public static class Output {
        public String rewrittenQuery;
        public String changes;
    }
}
