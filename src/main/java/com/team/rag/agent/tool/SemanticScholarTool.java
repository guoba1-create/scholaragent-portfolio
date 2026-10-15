package com.team.rag.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * Semantic Scholar 论文搜索工具 —— 通过 Semantic Scholar API 搜索学术论文。
 *
 * <p>职责：为 ReAct Agent 提供外部学术论文搜索能力，补充本地文档库检索。
 *
 * <p>使用 Semantic Scholar Graph API (api.semanticscholar.org)：
 * <ul>
 *   <li>支持关键词搜索、标题搜索</li>
 *   <li>返回论文标题、摘要、作者、引用数、DOI 等元数据</li>
 *   <li>支持限制返回数量</li>
 * </ul>
 */
@Component
public class SemanticScholarTool implements ScholarAgentTool<SemanticScholarTool.Input, SemanticScholarTool.Output> {

    private static final Logger log = LoggerFactory.getLogger(SemanticScholarTool.class);
    private static final String API_BASE = "https://api.semanticscholar.org/graph/v1";

    private final WebClient webClient;

    public SemanticScholarTool() {
        this.webClient = WebClient.builder()
                .baseUrl(API_BASE)
                .build();
    }

    @Override
    public String name() {
        return "search_papers";
    }

    @Override
    public String description() {
        return "Search for academic papers on Semantic Scholar. Use for finding papers by keywords, " +
                "verifying citations, or discovering related work. Returns paper titles, abstracts, " +
                "authors, citation counts, and DOIs.";
    }

    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("Semantic Scholar search input");
        schema.addProperty("query", ToolSchema.PropertySchema.string(
                "Search query keywords or paper title. Required."));
        schema.addProperty("limit", ToolSchema.PropertySchema.integer(
                "Maximum number of results to return. Default 10, max 50."));
        schema.addProperty("year", ToolSchema.PropertySchema.string(
                "Filter by publication year range, e.g. '2020-2025' or '2024-'."));
        schema.addProperty("fieldsOfStudy", ToolSchema.PropertySchema.string(
                "Filter by field of study, e.g. 'Computer Science', 'Medicine'."));
        schema.required("query");
        return schema;
    }

    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("Semantic Scholar search output");
        schema.addProperty("totalResults", ToolSchema.PropertySchema.integer("Total number of matching papers."));
        schema.addProperty("papers", ToolSchema.PropertySchema.array("List of paper objects with title, abstract, authors, etc."));
        return schema;
    }

    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        long start = System.currentTimeMillis();
        if (input == null || input.query == null || input.query.isBlank()) {
            return ToolResult.failure("INVALID_INPUT", "query is required", System.currentTimeMillis() - start);
        }

        int limit = input.limit != null ? Math.min(input.limit, 50) : 10;

        try {
            // Build request URL
            String url = "/paper/search?query=" + encode(input.query)
                    + "&limit=" + limit
                    + "&fields=title,abstract,authors,year,citationCount,url,externalIds,venue,publicationTypes";

            if (input.year != null && !input.year.isBlank()) {
                url += "&year=" + encode(input.year);
            }
            if (input.fieldsOfStudy != null && !input.fieldsOfStudy.isBlank()) {
                url += "&fieldsOfStudy=" + encode(input.fieldsOfStudy);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            Output output = new Output();
            if (response != null) {
                output.totalResults = toInt(response.get("total"));
                Object data = response.get("data");
                if (data instanceof List<?> papers) {
                    output.papers = new ArrayList<>();
                    for (Object paper : papers) {
                        if (paper instanceof Map<?, ?> p) {
                            Map<String, Object> paperMap = new LinkedHashMap<>();
                            paperMap.put("paperId", p.get("paperId"));
                            paperMap.put("title", p.get("title"));
                            paperMap.put("abstract_", truncate(toStr(p.get("abstract")), 500));
                            paperMap.put("year", p.get("year"));
                            paperMap.put("citationCount", p.get("citationCount"));
                            paperMap.put("url", p.get("url"));
                            paperMap.put("venue", p.get("venue"));
                            // Extract author names
                            Object authors = p.get("authors");
                            if (authors instanceof List<?> authorList) {
                                List<String> names = new ArrayList<>();
                                for (Object a : authorList) {
                                    if (a instanceof Map<?, ?> am) {
                                        names.add(toStr(am.get("name")));
                                    }
                                }
                                paperMap.put("authors", names);
                            }
                            // Extract DOI
                            Object extIds = p.get("externalIds");
                            if (extIds instanceof Map<?, ?> ids) {
                                paperMap.put("doi", ids.get("DOI"));
                                paperMap.put("arxivId", ids.get("ArXiv"));
                            }
                            output.papers.add(paperMap);
                        }
                    }
                }
            } else {
                output.totalResults = 0;
                output.papers = List.of();
            }

            long latency = System.currentTimeMillis() - start;
            return ToolResult.success(output, latency);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Semantic Scholar API error | query={}, error={}", input.query, e.getMessage());
            return ToolResult.failure("API_ERROR", "Semantic Scholar API error: " + e.getMessage(), latency);
        }
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public ToolType type() {
        return ToolType.RETRIEVAL;
    }

    @Override
    public int priority() {
        return 3; // Higher priority than local tools for external search
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available (external API, no local dependency)
    }

    private String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private String toStr(Object o) {
        return o != null ? o.toString() : "";
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    public static class Input {
        public String query;
        public Integer limit;
        public String year;
        public String fieldsOfStudy;
    }

    public static class Output {
        public int totalResults;
        public List<Map<String, Object>> papers;
    }
}
