package com.team.rag.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * Web 搜索工具 —— 通过搜索引擎 API 搜索互联网内容。
 *
 * <p>支持多种搜索后端：
 * <ul>
 *   <li>SearXNG（自托管，无需 API Key）</li>
 *   <li>Brave Search（需要 API Key）</li>
 * </ul>
 *
 * <p>用于补充学术论文搜索（SemanticScholarTool）的通用网页搜索能力。
 */
@Component
public class WebSearchTool implements ScholarAgentTool<WebSearchTool.Input, WebSearchTool.Output> {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    @Value("${agent.search.searxng-url:}")
    private String searxngUrl;

    @Value("${agent.search.brave-api-key:}")
    private String braveApiKey;

    private final WebClient webClient = WebClient.create();

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web for general information, news, documentation, or non-academic content. " +
                "Use when the question requires up-to-date information beyond academic papers, " +
                "such as software documentation, current events, or practical guides.";
    }

    @Override
    public ToolSchema inputSchema() {
        ToolSchema schema = new ToolSchema("Web search input");
        schema.addProperty("query", ToolSchema.PropertySchema.string(
                "Search query. Required."));
        schema.addProperty("limit", ToolSchema.PropertySchema.integer(
                "Maximum number of results. Default 5, max 20."));
        schema.addProperty("language", ToolSchema.PropertySchema.string(
                "Search language code, e.g. 'zh-CN', 'en'. Default 'auto'."));
        schema.required("query");
        return schema;
    }

    @Override
    public ToolSchema outputSchema() {
        ToolSchema schema = new ToolSchema("Web search output");
        schema.addProperty("totalResults", ToolSchema.PropertySchema.integer("Total results found."));
        schema.addProperty("results", ToolSchema.PropertySchema.array(
                "List of search results with title, url, snippet."));
        return schema;
    }

    @Override
    public ToolResult<Output> execute(Input input, ToolContext context) {
        long start = System.currentTimeMillis();
        if (input == null || input.query == null || input.query.isBlank()) {
            return ToolResult.failure("INVALID_INPUT", "query is required", System.currentTimeMillis() - start);
        }

        int limit = input.limit != null ? Math.min(input.limit, 20) : 5;

        try {
            Output output;
            if (searxngUrl != null && !searxngUrl.isBlank()) {
                output = searchSearxng(input.query, limit, input.language);
            } else if (braveApiKey != null && !braveApiKey.isBlank()) {
                output = searchBrave(input.query, limit);
            } else {
                return ToolResult.failure("NO_SEARCH_BACKEND",
                        "No search backend configured. Set agent.search.searxng-url or agent.search.brave-api-key",
                        System.currentTimeMillis() - start);
            }

            long latency = System.currentTimeMillis() - start;
            return ToolResult.success(output, latency);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Web search error | query={}, error={}", input.query, e.getMessage());
            return ToolResult.failure("SEARCH_ERROR", "Web search error: " + e.getMessage(), latency);
        }
    }

    private Output searchSearxng(String query, int limit, String language) {
        String lang = (language != null && !language.isBlank()) ? language : "auto";
        String url = searxngUrl.replaceAll("/+$", "") + "/search"
                + "?q=" + encode(query)
                + "&format=json"
                + "&pageno=1"
                + "&language=" + encode(lang);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        Output output = new Output();
        output.results = new ArrayList<>();

        if (response != null) {
            Object results = response.get("results");
            if (results instanceof List<?> items) {
                output.totalResults = items.size();
                int count = 0;
                for (Object item : items) {
                    if (count >= limit) break;
                    if (item instanceof Map<?, ?> r) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("title", r.get("title"));
                        result.put("url", r.get("url"));
                        result.put("snippet", r.get("content"));
                        result.put("engine", r.get("engine"));
                        output.results.add(result);
                        count++;
                    }
                }
            }
        }
        return output;
    }

    private Output searchBrave(String query, int limit) {
        String url = "https://api.search.brave.com/res/v1/web/search"
                + "?q=" + encode(query)
                + "&count=" + limit;

        @SuppressWarnings("unchecked")
        Map<String, Object> response = webClient.get()
                .uri(url)
                .header("X-Subscription-Token", braveApiKey)
                .header("Accept", "application/json")
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        Output output = new Output();
        output.results = new ArrayList<>();

        if (response != null) {
            Object web = response.get("web");
            if (web instanceof Map<?, ?> webMap) {
                Object results = webMap.get("results");
                if (results instanceof List<?> items) {
                    output.totalResults = items.size();
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> r) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("title", r.get("title"));
                            result.put("url", r.get("url"));
                            result.put("snippet", r.get("description"));
                            output.results.add(result);
                        }
                    }
                }
            }
        }
        return output;
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
        return 4;
    }

    @Override
    public boolean isAvailable() {
        return (searxngUrl != null && !searxngUrl.isBlank())
                || (braveApiKey != null && !braveApiKey.isBlank());
    }

    private String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    public static class Input {
        public String query;
        public Integer limit;
        public String language;
    }

    public static class Output {
        public int totalResults;
        public List<Map<String, Object>> results;
    }
}
