package com.team.rag.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.cache.LlmResponseCacheService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ContextSemanticCompressionService {

    private static final String PROMPT_VERSION = "context-summary-v3";
    private static final Pattern CITATION = Pattern.compile("\\[E\\d+]", Pattern.CASE_INSENSITIVE);

    private final ObjectProvider<ChatLanguageModel> modelProvider;
    private final LlmResponseCacheService cacheService;
    private final TokenEstimator tokenEstimator;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String compressionModelName;

    public ContextSemanticCompressionService(
            ObjectProvider<ChatLanguageModel> modelProvider,
            LlmResponseCacheService cacheService,
            TokenEstimator tokenEstimator,
            ObjectMapper objectMapper,
            @Value("${context.model-compression-enabled:true}") boolean enabled,
            @Value("${context.compression-model-name:configured-small-model}") String compressionModelName) {
        this.modelProvider = modelProvider;
        this.cacheService = cacheService;
        this.tokenEstimator = tokenEstimator;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.compressionModelName = compressionModelName;
    }

    public CompressionResult compress(ContextSegmentType type, String content, int targetTokens) {
        if (!enabled || content == null || content.isBlank() || targetTokens <= 0) {
            return CompressionResult.notApplied(content);
        }
        ChatLanguageModel model = modelProvider.getIfAvailable();
        if (model == null) {
            return CompressionResult.notApplied(content);
        }

        String prompt = """
                你是上下文压缩器。请压缩下面内容，不得引入新事实。
                区分已确认事实、推断、未知、约束、错误和下一步。
                必须原样保留所有形如 [E1]、[E2] 的引用标识。
                只输出以下 JSON，不要 Markdown：
                {"facts":[],"inferences":[],"unknowns":[],"constraints":[],"errors":[],"nextSteps":[],"citations":[]}
                所有字段必须是字符串数组，citations 必须包含原文中的全部引用标识。
                目标不超过 %d tokens。

                类型：%s
                内容：
                %s
                """.formatted(targetTokens, type, content);
        String namespace = "context-summary:" + PROMPT_VERSION + ":" + type.name().toLowerCase();
        String cached = cacheService.get(namespace, prompt);
        if (cached != null && !cached.isBlank()) {
            return parseAndValidate(content, cached, targetTokens, true);
        }
        try {
            String compressed = cacheService.getOrCompute(namespace, prompt, () -> model.generate(prompt));
            return parseAndValidate(content, compressed, targetTokens, false);
        } catch (RuntimeException ex) {
            return CompressionResult.notApplied(content);
        }
    }

    CompressionResult parseAndValidate(String original, String candidate,
                                       int targetTokens, boolean cacheHit) {
        if (candidate == null || candidate.isBlank()) {
            return CompressionResult.notApplied(original);
        }
        try {
            JsonNode root = objectMapper.readTree(candidate);
            List<String> fields = List.of(
                    "facts", "inferences", "unknowns", "constraints", "errors", "nextSteps", "citations");
            for (String field : fields) {
                if (!root.has(field) || !root.get(field).isArray()) {
                    return CompressionResult.notApplied(original);
                }
            }
            Set<String> requiredCitations = citations(original);
            Set<String> returnedCitations = new LinkedHashSet<>();
            root.get("citations").forEach(item -> returnedCitations.add(item.asText()));
            if (!returnedCitations.containsAll(requiredCitations)) {
                return CompressionResult.notApplied(original);
            }
            String rendered = render(root, fields);
            int renderedTokens = tokenEstimator.estimate(rendered);
            if (rendered.isBlank()
                    || renderedTokens >= tokenEstimator.estimate(original)
                    || renderedTokens > targetTokens) {
                return CompressionResult.notApplied(original);
            }
            return new CompressionResult(rendered, true, compressionModelName, cacheHit);
        } catch (Exception error) {
            return CompressionResult.notApplied(original);
        }
    }

    private String render(JsonNode root, List<String> fields) {
        List<String> lines = new ArrayList<>();
        for (String field : fields) {
            List<String> values = new ArrayList<>();
            root.get(field).forEach(item -> {
                String value = item.asText().trim();
                if (!value.isBlank()) values.add(value);
            });
            if (!values.isEmpty()) {
                lines.add(field + ": " + String.join(" | ", values));
            }
        }
        return String.join("\n", lines);
    }

    private Set<String> citations(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(value == null ? "" : value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    public record CompressionResult(String text, boolean applied, String modelUsed, boolean cacheHit) {
        static CompressionResult notApplied(String text) {
            return new CompressionResult(text == null ? "" : text, false, null, false);
        }
    }
}
