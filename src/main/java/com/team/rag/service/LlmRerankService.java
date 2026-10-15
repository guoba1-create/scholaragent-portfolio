package com.team.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.cache.LlmResponseCacheService;
import com.team.rag.config.RagRuntimeProperties;
import com.team.rag.entity.RerankResult;
import com.team.rag.entity.RetrievalHit;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LLM 精排服务（Rerank）
 *
 * <p>五层增强检索链的第三层。向量检索是"粗排"（语义相似度），可能存在噪声。
 * 本服务使用 LLM 对每个候选文档重新打分（0-1），实现更精准的相关性排序。</p>
 *
 * <p><b>核心流程：</b></p>
 * <ol>
 *   <li>将候选文档列表格式化为 prompt</li>
 *   <li>调用 LLM 为每个文档打分（0-1）并给出理由</li>
 *   <li>解析 JSON 响应，按分数降序排列</li>
 *   <li>返回 topK 个最相关的结果</li>
 * </ol>
 *
 * <p><b>降级策略：</b>LLM 调用失败时，回退到按原始向量分数排序。</p>
 *
 * <p><b>缓存：</b>支持通过 LlmResponseCacheService 缓存 rerank 结果，避免重复调用。</p>
 *
 * <p><b>两种输入类型：</b></p>
 * <ul>
 *   <li>{@link EmbeddingMatch} — 纯向量检索的结果</li>
 *   <li>{@link RetrievalHit} — 混合检索（dense + sparse）的结果，prompt 中会包含两种分数</li>
 * </ul>
 *
 * @see RetrievalFormattingService — 调用方，负责检索+格式化
 * @see LlmResponseCacheService — 缓存层
 */
@Service
public class LlmRerankService {

    private static final Logger log = LoggerFactory.getLogger(LlmRerankService.class);

    private final ObjectMapper objectMapper;           // JSON 解析器，用于解析 LLM 返回的排名 JSON
    private final RerankAssistant rerankAssistant;     // LangChain4j AiServices 代理，封装 LLM 调用
    private final LlmResponseCacheService cacheService; // 两级缓存服务，避免重复 rerank 调用
    private final RagRuntimeProperties runtimeProperties; // 运行时配置（rerank 开关、缓存开关等）

    public LlmRerankService(ChatLanguageModel chatModel,
                            ObjectMapper objectMapper,
                            LlmResponseCacheService cacheService,
                            RagRuntimeProperties runtimeProperties) {
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
        this.runtimeProperties = runtimeProperties;
        this.rerankAssistant = AiServices.builder(RerankAssistant.class)
                .chatLanguageModel(chatModel)
                .build();
    }

    /**
     * 对纯向量检索结果进行 LLM 精排
     *
     * @param query      用户查询
     * @param candidates 向量检索的候选结果列表
     * @param topK       返回的最大结果数
     * @return 按 LLM 打分降序排列的精排结果，LLM 失败时回退到原始向量分数排序
     */
    public List<RerankResult> rerank(String query, List<EmbeddingMatch<TextSegment>> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }

        long start = System.currentTimeMillis();
        try {
            List<RerankResult> results = performRerank(query, candidates);
            results.sort(Comparator.comparingDouble(RerankResult::getRerankScore).reversed());
            int resultCount = Math.min(Math.max(1, topK), results.size());
            log.info("LLM rerank completed | candidates={}, returned={}, elapsedMs={}",
                    candidates.size(), resultCount, System.currentTimeMillis() - start);
            return new ArrayList<>(results.subList(0, resultCount));
        } catch (Exception e) {
            log.warn("LLM rerank failed, fallback to vector ranking | error={}", e.getMessage());
            return fallbackRerank(candidates, topK);
        }
    }

    /**
     * 对混合检索结果（dense + sparse）进行 LLM 精排
     * <p>与 {@link #rerank} 不同，此方法的 prompt 中会同时展示向量分数和稀疏分数，
     * 让 LLM 综合两种信号做出更准确的排序。</p>
     *
     * @param query      用户查询
     * @param candidates 混合检索的候选结果列表（包含 vectorScore 和 sparseScore）
     * @param topK       返回的最大结果数
     * @return 按 LLM 打分降序排列的精排结果
     */
    public List<RerankResult> rerankHits(String query, List<RetrievalHit> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            List<RerankResult> results = performHitRerank(query, candidates);
            results.sort(Comparator.comparingDouble(RerankResult::getRerankScore).reversed());
            int resultCount = Math.min(Math.max(1, topK), results.size());
            log.info("LLM hybrid rerank completed | candidates={}, returned={}, elapsedMs={}",
                    candidates.size(), resultCount, System.currentTimeMillis() - start);
            return new ArrayList<>(results.subList(0, resultCount));
        } catch (Exception e) {
            log.warn("LLM hybrid rerank failed, fallback to merged ranking | error={}", e.getMessage());
            return fallbackHitRerank(candidates, topK);
        }
    }

    public List<List<RerankResult>> batchRerank(List<String> queries,
                                                List<List<EmbeddingMatch<TextSegment>>> candidatesList,
                                                int topK) {
        List<List<RerankResult>> results = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            List<EmbeddingMatch<TextSegment>> candidates = i < candidatesList.size()
                    ? candidatesList.get(i)
                    : List.of();
            results.add(rerank(queries.get(i), candidates, topK));
        }
        return results;
    }

    /**
     * 执行纯向量检索结果的 rerank：构建 prompt → 调用 LLM → 解析响应
     */
    private List<RerankResult> performRerank(String query, List<EmbeddingMatch<TextSegment>> candidates) throws Exception {
        String prompt = buildRerankPrompt(query, buildDocumentsText(candidates), candidates.size());
        String response = runRerankPrompt(prompt);
        return parseRerankResponse(response, candidates);
    }

    private List<RerankResult> performHitRerank(String query, List<RetrievalHit> candidates) throws Exception {
        String prompt = buildHybridRerankPrompt(query, buildHitDocumentsText(candidates), candidates.size());
        String response = runRerankPrompt(prompt);
        return parseHitRerankResponse(response, candidates);
    }

    /**
     * 执行 rerank prompt，支持缓存开关
     * <p>如果缓存开启，先查 L1/L2 缓存，未命中才调用 LLM；
     * 如果缓存关闭，直接调用 LLM。</p>
     */
    private String runRerankPrompt(String prompt) {
        return runtimeProperties.isRerankCacheEnabled()
                ? cacheService.getOrCompute("rerank-v1", prompt, () -> rerankAssistant.rerank(prompt))
                : rerankAssistant.rerank(prompt);
    }

    private String buildDocumentsText(List<EmbeddingMatch<TextSegment>> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            EmbeddingMatch<TextSegment> match = candidates.get(i);
            TextSegment segment = match.embedded();
            String source = segment != null && segment.metadata() != null
                    ? segment.metadata().getString("source")
                    : "unknown";
            String content = segment == null ? "" : segment.text();
            sb.append("--- [Document ").append(i + 1).append("] ---\n");
            sb.append("Source: ").append(source).append("\n");
            sb.append("Dense score: ").append(String.format("%.4f", match.score())).append("\n");
            sb.append("Content: ").append(content).append("\n\n");
        }
        return sb.toString();
    }

    private String buildHitDocumentsText(List<RetrievalHit> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalHit hit = candidates.get(i);
            sb.append("--- [Document ").append(i + 1).append("] ---\n");
            sb.append("Source: ").append(defaultString(hit.title, hit.source, "unknown")).append("\n");
            if (hit.vectorScore != null) {
                sb.append("Dense score: ").append(String.format("%.4f", hit.vectorScore)).append("\n");
            }
            if (hit.sparseScore != null) {
                sb.append("Sparse score: ").append(String.format("%.4f", hit.sparseScore)).append("\n");
            }
            if (hit.score != null) {
                sb.append("Hybrid score: ").append(String.format("%.4f", hit.score)).append("\n");
            }
            if (hit.section != null && !hit.section.isBlank()) {
                sb.append("Section: ").append(hit.section).append("\n");
            }
            sb.append("Content: ").append(defaultString(hit.content, "")).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建纯向量检索的 rerank prompt
     * <p>指示 LLM 为每个候选文档打分（0-1），返回 JSON 格式的排名列表。</p>
     */
    private String buildRerankPrompt(String query, String documentsText, int docCount) {
        return """
                You are a research retrieval reranker.
                Score each candidate document for relevance to the user query.

                User query:
                %s

                Candidates:
                %s

                Return JSON only:
                {
                  "rankings": [
                    {"doc_id": 1, "score": 0.95, "reason": "short reason"}
                  ]
                }

                Rules:
                1. Score every one of the %d candidates.
                2. score must be between 0 and 1.
                3. reason must be short.
                4. Return JSON only.
                """.formatted(query, documentsText, docCount);
    }

    /**
     * 构建混合检索的 rerank prompt
     * <p>与纯向量版本不同，此 prompt 会同时展示 dense score 和 sparse score，
     * 让 LLM 综合语义相似度和关键词匹配度做出更准确的排序。</p>
     */
    private String buildHybridRerankPrompt(String query, String documentsText, int docCount) {
        return """
                You are reranking research evidence candidates from a hybrid dense+sparse retrieval system.
                Dense score captures semantic similarity. Sparse score captures exact term overlap.
                Use both, but prioritize whether the content can directly help answer the user query.

                User query:
                %s

                Candidates:
                %s

                Return JSON only:
                {
                  "rankings": [
                    {"doc_id": 1, "score": 0.95, "reason": "short reason"}
                  ]
                }

                Rules:
                1. Score every one of the %d candidates.
                2. score must be between 0 and 1.
                3. reason must be short and concrete.
                4. Prefer candidates that are both relevant and grounded.
                5. Return JSON only.
                """.formatted(query, documentsText, docCount);
    }

    private List<RerankResult> parseRerankResponse(String response,
                                                   List<EmbeddingMatch<TextSegment>> candidates) {
        List<RerankResult> results = new ArrayList<>();
        try {
            JsonNode rankingsNode = objectMapper.readTree(extractJsonContent(response)).get("rankings");
            if (rankingsNode == null || !rankingsNode.isArray()) {
                return createDefaultResults(candidates);
            }

            for (JsonNode ranking : rankingsNode) {
                int docId = ranking.has("doc_id") ? ranking.get("doc_id").asInt() : 0;
                double score = ranking.has("score") ? ranking.get("score").asDouble() : 0.5;
                String reason = ranking.has("reason") ? ranking.get("reason").asText() : "no_reason";
                if (docId >= 1 && docId <= candidates.size()) {
                    results.add(new RerankResult(candidates.get(docId - 1), score, reason));
                }
            }

            if (results.size() < candidates.size()) {
                fillMissingResults(results, candidates);
            }
            return results;
        } catch (Exception e) {
            return createDefaultResults(candidates);
        }
    }

    private List<RerankResult> parseHitRerankResponse(String response,
                                                      List<RetrievalHit> candidates) {
        List<RerankResult> results = new ArrayList<>();
        try {
            JsonNode rankingsNode = objectMapper.readTree(extractJsonContent(response)).get("rankings");
            if (rankingsNode == null || !rankingsNode.isArray()) {
                return createDefaultHitResults(candidates);
            }

            for (JsonNode ranking : rankingsNode) {
                int docId = ranking.has("doc_id") ? ranking.get("doc_id").asInt() : 0;
                double score = ranking.has("score") ? ranking.get("score").asDouble() : 0.5;
                String reason = ranking.has("reason") ? ranking.get("reason").asText() : "no_reason";
                if (docId >= 1 && docId <= candidates.size()) {
                    results.add(new RerankResult(copyHit(candidates.get(docId - 1)), score, reason));
                }
            }

            if (results.size() < candidates.size()) {
                fillMissingHitResults(results, candidates);
            }
            return results;
        } catch (Exception e) {
            return createDefaultHitResults(candidates);
        }
    }

    private String extractJsonContent(String response) {
        String trimmed = response == null ? "" : response.trim();
        if (trimmed.startsWith("```json")) {
            int endIndex = trimmed.lastIndexOf("```");
            if (endIndex > 7) {
                return trimmed.substring(7, endIndex).trim();
            }
        }
        if (trimmed.startsWith("```")) {
            int endIndex = trimmed.lastIndexOf("```");
            if (endIndex > 3) {
                return trimmed.substring(3, endIndex).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private List<RerankResult> createDefaultResults(List<EmbeddingMatch<TextSegment>> candidates) {
        List<RerankResult> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : candidates) {
            results.add(new RerankResult(match, match.score(), "fallback_vector_score"));
        }
        return results;
    }

    private List<RerankResult> createDefaultHitResults(List<RetrievalHit> candidates) {
        List<RerankResult> results = new ArrayList<>();
        for (RetrievalHit hit : candidates) {
            double score = hit.score != null ? hit.score : 0.0;
            results.add(new RerankResult(copyHit(hit), score, "fallback_hybrid_score"));
        }
        return results;
    }

    private void fillMissingResults(List<RerankResult> results, List<EmbeddingMatch<TextSegment>> candidates) {
        for (EmbeddingMatch<TextSegment> candidate : candidates) {
            boolean exists = results.stream().anyMatch(item -> item.getOriginalMatch() == candidate);
            if (!exists) {
                results.add(new RerankResult(candidate, candidate.score() * 0.8, "missing_score_fallback"));
            }
        }
    }

    private void fillMissingHitResults(List<RerankResult> results, List<RetrievalHit> candidates) {
        for (RetrievalHit candidate : candidates) {
            boolean exists = results.stream().anyMatch(item ->
                    item.getRetrievalHit() != null
                            && sameHit(item.getRetrievalHit(), candidate));
            if (!exists) {
                double score = candidate.score != null ? candidate.score * 0.8 : 0.0;
                results.add(new RerankResult(copyHit(candidate), score, "missing_hybrid_score_fallback"));
            }
        }
    }

    private boolean sameHit(RetrievalHit left, RetrievalHit right) {
        return defaultString(left.documentId, "")
                .equals(defaultString(right.documentId, ""))
                && defaultString(left.chunkIndex == null ? null : String.valueOf(left.chunkIndex), "")
                .equals(defaultString(right.chunkIndex == null ? null : String.valueOf(right.chunkIndex), ""))
                && defaultString(left.pageNumber == null ? null : String.valueOf(left.pageNumber), "")
                .equals(defaultString(right.pageNumber == null ? null : String.valueOf(right.pageNumber), ""));
    }

    private RetrievalHit copyHit(RetrievalHit source) {
        RetrievalHit copy = new RetrievalHit();
        copy.content = source.content;
        copy.source = source.source;
        copy.title = source.title;
        copy.section = source.section;
        copy.documentId = source.documentId;
        copy.documentHash = source.documentHash;
        copy.sourcePath = source.sourcePath;
        copy.pageNumber = source.pageNumber;
        copy.chunkIndex = source.chunkIndex;
        copy.score = source.score;
        copy.vectorScore = source.vectorScore;
        copy.sparseScore = source.sparseScore;
        copy.reason = source.reason;
        copy.embeddingId = source.embeddingId;
        copy.metadata.putAll(source.metadata);
        return copy;
    }

    private List<RerankResult> fallbackRerank(List<EmbeddingMatch<TextSegment>> candidates, int topK) {
        List<RerankResult> results = createDefaultResults(candidates);
        results.sort(Comparator.comparingDouble(RerankResult::getRerankScore).reversed());
        return new ArrayList<>(results.subList(0, Math.min(Math.max(1, topK), results.size())));
    }

    private List<RerankResult> fallbackHitRerank(List<RetrievalHit> candidates, int topK) {
        List<RerankResult> results = createDefaultHitResults(candidates);
        results.sort(Comparator.comparingDouble(RerankResult::getRerankScore).reversed());
        return new ArrayList<>(results.subList(0, Math.min(Math.max(1, topK), results.size())));
    }

    private String defaultString(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    interface RerankAssistant {
        String rerank(String prompt);
    }
}
