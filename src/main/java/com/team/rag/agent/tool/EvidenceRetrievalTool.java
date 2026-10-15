package com.team.rag.agent.tool;

import com.team.rag.entity.RetrievalHit;
import com.team.rag.service.RetrievalFormattingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 证据检索工具
 *
 * <p>封装检索和重排序能力，用于 Claim Verification 工作流
 */
@Component
public class EvidenceRetrievalTool implements ScholarAgentTool<EvidenceRetrievalTool.Input, EvidenceRetrievalTool.Output> {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRetrievalTool.class);

    private final RetrievalFormattingService retrievalService;

    public EvidenceRetrievalTool(RetrievalFormattingService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public String name() {
        return "retrieve_evidence";
    }

    @Override
    public String description() {
        return "检索并重排证据片段，用于支持或反驳学术断言";
    }

    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("证据检索输入");
        schema.addProperty("query", ToolSchema.PropertySchema.string("检索查询"));
        schema.addProperty("claim", ToolSchema.PropertySchema.string("原始断言"));
        schema.addProperty("topK", ToolSchema.PropertySchema.integer("返回结果数量").defaultValue(5));
        schema.addProperty("paperScope", ToolSchema.PropertySchema.string("论文范围").enumValues("current_library", "all"));
        schema.required("query", "claim");
        return schema;
    }

    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("证据检索输出");
        schema.addProperty("formattedEvidence", ToolSchema.PropertySchema.string("格式化后的证据文本"));
        schema.addProperty("totalResults", ToolSchema.PropertySchema.integer("结果数量"));
        return schema;
    }

    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        log.info("执行证据检索 | query={}, claim={}", input.query, truncate(input.claim, 50));

        long startTime = System.currentTimeMillis();

        try {
            List<RetrievalHit> hits = retrievalService.retrieveLiteratureHits(
                    input.query,
                    context.getResearchGroupId(),
                    input.topK,
                    0.3,
                    true);

            Output output = new Output();
            output.evidenceSpans = toEvidenceSpans(hits);
            output.totalResults = output.evidenceSpans.size();
            output.formattedEvidence = formatHits(output.evidenceSpans);

            long latencyMs = System.currentTimeMillis() - startTime;
            log.info("证据检索完成 | results={}, latency={}ms", output.totalResults, latencyMs);

            return ToolResult.success(output, latencyMs);

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.error("证据检索失败 | error={}", e.getMessage(), e);
            return ToolResult.failure("RETRIEVAL_ERROR", e.getMessage(), latencyMs);
        }
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public ScholarAgentTool.ToolType type() {
        return ScholarAgentTool.ToolType.RETRIEVAL;
    }

    private int countResults(String formatted) {
        int count = 0;
        int idx = 0;
        while ((idx = formatted.indexOf("--- [文献", idx)) != -1) {
            count++;
            idx++;
        }
        return count;
    }

    private List<EvidenceSpan> toEvidenceSpans(List<RetrievalHit> hits) {
        List<EvidenceSpan> spans = new java.util.ArrayList<>();
        if (hits == null) {
            return spans;
        }
        for (RetrievalHit hit : hits) {
            EvidenceSpan span = new EvidenceSpan();
            span.text = hit.content;
            span.title = hit.title != null ? hit.title : hit.source;
            span.section = hit.section;
            span.chunkIndex = hit.chunkIndex == null ? -1 : hit.chunkIndex;
            span.pageNumber = hit.pageNumber;
            span.relevanceScore = hit.score == null ? 0.0 : hit.score;
            span.documentId = hit.documentId;
            span.sourcePath = hit.sourcePath;
            spans.add(span);
        }
        return spans;
    }

    private String formatHits(List<EvidenceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return "[Retrieval results empty]\nNo relevant evidence was found in the current literature library.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Retrieved evidence spans] total ").append(spans.size()).append("\n\n");
        for (int i = 0; i < spans.size(); i++) {
            EvidenceSpan span = spans.get(i);
            sb.append("--- [Evidence ").append(i + 1).append("] ---\n");
            sb.append("Source: ").append(defaultString(span.title, "Unknown")).append("\n");
            sb.append("Section: ").append(defaultString(span.section, "unknown")).append("\n");
            if (span.pageNumber != null) {
                sb.append("Page: ").append(span.pageNumber).append("\n");
            }
            sb.append("Chunk: ").append(span.chunkIndex).append("\n");
            sb.append("Score: ").append(String.format("%.3f", span.relevanceScore)).append("\n");
            sb.append("Content: ").append(defaultString(span.text, "")).append("\n\n");
        }
        return sb.toString();
    }

    private List<EvidenceSpan> parseEvidenceSpans(String formatted) {
        List<EvidenceSpan> spans = new java.util.ArrayList<>();
        if (formatted == null || formatted.isEmpty()) return spans;

        String[] sections = formatted.split("--- \\[文献\\d+\\] ---");
        for (int i = 1; i < sections.length; i++) {
            EvidenceSpan span = new EvidenceSpan();
            String section = sections[i];

            // Extract source
            int sourceIdx = section.indexOf("来源：");
            if (sourceIdx >= 0) {
                String sourceLine = section.substring(sourceIdx + 3, section.indexOf("\n", sourceIdx));
                span.title = sourceLine.trim();
            }

            // Extract content
            int contentIdx = section.indexOf("内容：");
            if (contentIdx >= 0) {
                span.text = section.substring(contentIdx + 3).trim();
            }

            span.section = "main";
            span.chunkIndex = i - 1;
            spans.add(span);
        }
        return spans;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 输入类
     */
    public static class Input {
        public String query;
        public String claim;
        public int topK = 5;
        public String paperScope = "current_library";
    }

    /**
     * 输出类
     */
    public static class Output {
        public String formattedEvidence;
        public int totalResults;
        public List<EvidenceSpan> evidenceSpans = new java.util.ArrayList<>();
    }

    /**
     * 证据片段（供 ClaimVerificationWorkflow 等旧工作流使用）
     */
    public static class EvidenceSpan {
        public String text;
        public String title;
        public String section;
        public int chunkIndex;
        public Integer pageNumber;
        public double relevanceScore;
        public String documentId;
        public String sourcePath;
    }
}
