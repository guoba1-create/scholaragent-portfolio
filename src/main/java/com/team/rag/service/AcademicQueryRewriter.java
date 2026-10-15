package com.team.rag.service;

import com.team.rag.cache.LlmResponseCacheService;
import com.team.rag.config.RagRuntimeProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 学术查询改写服务（Query Rewrite）
 *
 * <p>五层增强检索链的第一层。用户的问题往往是口语化的、含指代的、不适合直接做向量检索的。
 * 本服务使用 LLM 将用户问题改写成更适合检索的 query。</p>
 *
 * <p><b>改写规则：</b></p>
 * <ol>
 *   <li>解析指代（"它" → 具体研究对象，如 "STRNN"）</li>
 *   <li>保留核心研究对象/方法/数据集/指标</li>
 *   <li>补充英文缩写和同义术语</li>
 *   <li>去除口语化表达，输出一行纯检索查询</li>
 * </ol>
 *
 * <p><b>示例：</b></p>
 * <ul>
 *   <li>输入："STRNN 怎么建模时空转移的？"</li>
 *   <li>输出："STRNN spatial-temporal transition modeling next-POI recommendation"</li>
 * </ul>
 *
 * <p><b>降级策略：</b>LLM 调用失败时，返回原始 query 不做改写。</p>
 *
 * @see RetrievalFormattingService — 改写后的 query 传给此服务做向量检索
 */
@Service
public class AcademicQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(AcademicQueryRewriter.class);
    /** 最多取最近 5 轮对话历史（10 条消息）作为改写上下文 */
    private static final int MAX_HISTORY_TURNS = 5;

    /**
     * 改写 prompt 模板
     * <p>包含历史对话和当前问题，指示 LLM 做查询改写。
     * 要求 LLM 补全指代、保留核心术语、输出一行纯检索查询。</p>
     */
    private static final String REWRITE_PROMPT_TEMPLATE = """
            你是一个学术检索查询优化助手。请把用户当前的问题改写成更适合向量检索和关键词匹配的查询。

            要求：
            1. 如果当前问题里有“它、这个方法、这个方向”之类指代，请结合历史对话补全主体。
            2. 保留核心研究对象、任务、方法、数据集、指标、应用场景。
            3. 可以补充常见英文缩写、同义术语、专业关键词，但不要胡乱扩展。
            4. 输出必须是一行纯检索查询，不要加解释，不要加前缀。

            历史对话：
            %s

            当前问题：
            %s
            """;

    private final ChatLanguageModel chatModel;
    private final LlmResponseCacheService cacheService;
    private final RagRuntimeProperties runtimeProperties;

    public AcademicQueryRewriter(ChatLanguageModel chatModel,
                                 LlmResponseCacheService cacheService,
                                 RagRuntimeProperties runtimeProperties) {
        this.chatModel = chatModel;
        this.cacheService = cacheService;
        this.runtimeProperties = runtimeProperties;
    }

    /**
     * 改写查询（简化接口）
     * <p>只返回改写后的 query 字符串，丢弃 applied/reason 信息。</p>
     */
    public String rewrite(List<ChatMessage> chatHistory, String currentQuery) {
        return rewriteDetailed(chatHistory, currentQuery).query();
    }

    /**
     * 改写查询（详细接口）
     * <p>返回 RewriteResult record，包含改写后的 query、是否实际改写、改写原因。</p>
     *
     * <p><b>执行流程：</b></p>
     * <ol>
     *   <li>空查询检查 → 直接返回空</li>
     *   <li>改写开关检查 → 关闭则返回原始 query</li>
     *   <li>查询过短检查 → 太短的 query 不改写（避免丢失信息）</li>
     *   <li>格式化对话历史 → 取最近 5 轮</li>
     *   <li>构建 prompt → 调用 LLM（带缓存）</li>
     *   <li>清理 LLM 输出 → 去除前缀、引号、标点</li>
     * </ol>
     *
     * @param chatHistory  对话历史（可为 null）
     * @param currentQuery 当前用户问题
     * @return RewriteResult(query, applied, reason)
     */
    public RewriteResult rewriteDetailed(List<ChatMessage> chatHistory, String currentQuery) {
        if (currentQuery == null || currentQuery.isBlank()) {
            return new RewriteResult("", false, "empty_query");
        }
        if (!runtimeProperties.isQueryRewriteEnabled()) {
            return new RewriteResult(currentQuery.trim(), false, "rewrite_disabled");
        }

        String trimmedQuery = currentQuery.trim();
        if (trimmedQuery.length() < runtimeProperties.getQueryRewriteMinLength()) {
            return new RewriteResult(trimmedQuery, false, "query_too_short");
        }

        String formattedHistory = formatChatHistory(chatHistory);
        String prompt = REWRITE_PROMPT_TEMPLATE.formatted(formattedHistory, trimmedQuery);

        try {
            String rewritten = cacheService.getOrCompute(
                    "query-rewrite-v1",
                    prompt,
                    () -> chatModel.generate(prompt));
            String cleaned = cleanRewrittenQuery(rewritten);
            if (cleaned.isBlank()) {
                return new RewriteResult(trimmedQuery, false, "empty_llm_result");
            }
            boolean applied = !cleaned.equals(trimmedQuery);
            log.info("Query rewrite completed | applied={}, original={}, rewritten={}",
                    applied, truncateForLog(trimmedQuery), truncateForLog(cleaned));
            return new RewriteResult(cleaned, applied, applied ? "rewritten" : "unchanged_after_cleaning");
        } catch (Exception e) {
            log.warn("Query rewrite failed, fallback to original query | error={}", e.getMessage());
            return new RewriteResult(trimmedQuery, false, "rewrite_error");
        }
    }

    public String rewriteWithFallback(String currentQuery) {
        return rewrite(null, currentQuery);
    }

    private String formatChatHistory(List<ChatMessage> chatHistory) {
        if (chatHistory == null || chatHistory.isEmpty()) {
            return "(no history)";
        }
        int startIndex = Math.max(0, chatHistory.size() - MAX_HISTORY_TURNS * 2);
        List<ChatMessage> recentHistory = chatHistory.subList(startIndex, chatHistory.size());
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : recentHistory) {
            String content = safeText(message);
            if (content == null || content.isBlank()) {
                continue;
            }
            sb.append(roleOf(message)).append(": ").append(content.trim()).append("\n");
        }
        return sb.isEmpty() ? "(no history)" : sb.toString().trim();
    }

    private String roleOf(ChatMessage message) {
        String className = message.getClass().getSimpleName().toLowerCase();
        if (className.contains("user") || className.contains("human")) {
            return "User";
        }
        if (className.contains("ai") || className.contains("assistant") || className.contains("system")) {
            return "Assistant";
        }
        return "Message";
    }

    private String safeText(ChatMessage message) {
        try {
            return message.text();
        } catch (Exception e) {
            return null;
        }
    }

    private String cleanRewrittenQuery(String rewrittenQuery) {
        if (rewrittenQuery == null) {
            return "";
        }
        String cleaned = rewrittenQuery.trim();
        String[] prefixes = {
                "改写为：", "改写为:", "改写后：", "改写后:",
                "Query:", "Query：", "查询:", "查询：",
                "检索词:", "检索词：", "结果:", "结果："
        };
        for (String prefix : prefixes) {
            if (cleaned.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length()).trim();
            }
        }
        cleaned = cleaned.replaceAll("^[\"']|[\"']$", "");
        cleaned = cleaned.replaceAll("[。！？；，、]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private String truncateForLog(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    public record RewriteResult(String query, boolean applied, String reason) {
    }
}
