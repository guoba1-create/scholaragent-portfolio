package com.team.rag.service;

import com.team.rag.config.RagRuntimeProperties;
import com.team.rag.entity.RerankResult;
import com.team.rag.entity.RetrievalHit;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索结果格式化服务 —— RAG 系统的核心检索编排层。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li><b>向量检索（Dense Retrieval）</b>：通过 EmbeddingModel 将查询文本编码为向量，
 *       再在 EmbeddingStore（底层可对接 Pinecone / Milvus 等）中做近邻搜索。</li>
 *   <li><b>稀疏检索（Sparse Retrieval）</b>：通过 {@link PineconeSparseRetrievalService}
 *       调用 Pinecone 的稀疏向量接口，捕获关键词级别的匹配信号。</li>
 *   <li><b>混合检索融合</b>：使用 <b>RRF（Reciprocal Rank Fusion）</b> 算法将 dense 和 sparse
 *       两条检索通道的结果按排名融合为统一分数，兼顾语义相似和关键词命中。</li>
 *   <li><b>LLM Rerank 精排</b>：在粗排候选集上调用大语言模型做语义重排序，
 *       进一步提升最终返回结果的相关性。</li>
 *   <li><b>结果格式化</b>：将结构化的检索命中转化为可读的文本片段，供上层 Prompt 拼接使用。</li>
 *   <li><b>熔断器模式</b>：当 EmbeddingStore 连续失败时，通过 {@code embeddingStoreAvailable}
 *       标志位快速失败（fail-fast），避免对下游造成雪崩。</li>
 * </ol>
 *
 * <h3>设计思想</h3>
 * <ul>
 *   <li>采用 <b>候选集放大</b> 策略：先检索 3 倍于最终需要数量的候选，再通过 rerank 筛选，
 *       以牺牲少量延迟换取召回率。</li>
 *   <li>各检索通道解耦，通过 RRF 在结果层面融合，便于独立调参和扩展新通道。</li>
 * </ul>
 *
 * <h3>在系统中的位置</h3>
 * <p>本服务位于 Controller / Agent 调用层之下、具体检索基础设施之上。
 * 上层只需传入查询文本和过滤条件，即可获得格式化的检索结果字符串。</p>
 */
@Service
public class RetrievalFormattingService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalFormattingService.class);

    /** 候选集倍数：实际检索数量 = maxResults * 该系数，为后续 rerank 预留充足候选 */
    private static final int DEFAULT_CANDIDATE_MULTIPLIER = 3;

    /** RRF 算法中的平滑常数 k，值越大则排名靠后的文档衰减越慢；60 是论文中的经典取值 */
    private static final int RRF_K = 60;

    /** 检索结果为空时返回给上层的提示文本 */
    private static final String EMPTY_RESULT_MESSAGE =
            "Retrieval results empty.\nNo relevant content was found in the current literature library.";

    /** 文本嵌入模型，负责将自然语言查询编码为稠密向量 */
    private final EmbeddingModel embeddingModel;

    /** 向量存储后端，支持基于向量相似度的近邻搜索 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** LLM 重排序服务，利用大模型对候选文档做精细相关性判断 */
    private final LlmRerankService llmRerankService;

    /** Pinecone 稀疏向量检索服务，提供基于关键词/词频的稀疏匹配能力 */
    private final PineconeSparseRetrievalService pineconeSparseRetrievalService;

    /** 运行时动态配置项（如是否启用 rerank、是否启用稀疏检索等） */
    private final RagRuntimeProperties runtimeProperties;

    /**
     * 熔断器标志位 —— 用于 EmbeddingStore 不可用时快速失败。
     * 使用 volatile 保证多线程可见性：一个线程发现故障后，其他线程能立即感知。
     * 注意：当前实现不会自动恢复（无半开状态），需要重启服务才能重置。
     */
    private volatile boolean embeddingStoreAvailable = true;

    /**
     * 构造函数，由 Spring 自动注入所有依赖。
     *
     * @param embeddingModel              文本嵌入模型（如 OpenAI / HuggingFace 提供的向量化模型）
     * @param embeddingStore              向量存储后端（如 Pinecone、Milvus 等）
     * @param llmRerankService            LLM 精排服务
     * @param pineconeSparseRetrievalService Pinecone 稀疏检索服务
     * @param runtimeProperties           运行时可变配置（支持热更新）
     */
    public RetrievalFormattingService(EmbeddingModel embeddingModel,
                                      EmbeddingStore<TextSegment> embeddingStore,
                                      LlmRerankService llmRerankService,
                                      PineconeSparseRetrievalService pineconeSparseRetrievalService,
                                      RagRuntimeProperties runtimeProperties) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.llmRerankService = llmRerankService;
        this.pineconeSparseRetrievalService = pineconeSparseRetrievalService;
        this.runtimeProperties = runtimeProperties;
    }

    /**
     * 带完整日志上下文的文献检索入口。
     * 记录查询长度、研究组和用户信息后，委托给核心检索流程。
     *
     * @param query            用户查询文本
     * @param researchGroupId  研究组 ID，用于过滤该组下的文献库
     * @param userId           用户 ID（当前未用于文献过滤，预留扩展）
     * @param maxResults       最终返回的最大结果数
     * @param minScore         向量相似度的最低阈值，低于此分数的结果会被丢弃
     * @return 格式化后的检索结果文本，可直接拼入 Prompt
     */
    public String retrieveAndFormat(String query, String researchGroupId, String userId,
                                    int maxResults, double minScore) {
        log.info("Start combined retrieval | queryLength={}, researchGroupId={}, userId={}",
                query.length(), researchGroupId, userId);
        return retrieveLiteratureAndFormat(query, researchGroupId, maxResults, minScore, true);
    }

    /**
     * 文献检索并格式化（默认启用 rerank）。
     *
     * @param query            用户查询文本
     * @param researchGroupId  研究组 ID
     * @param maxResults       最大返回结果数
     * @param minScore         最低相似度阈值
     * @return 格式化后的检索结果文本
     */
    public String retrieveLiteratureAndFormat(String query, String researchGroupId,
                                              int maxResults, double minScore) {
        return retrieveLiteratureAndFormat(query, researchGroupId, maxResults, minScore, true);
    }

    /**
     * 文献检索并格式化（可控制是否启用 rerank）。
     * 先执行检索拿到结构化命中列表，再通过 {@link #formatRetrievalHits} 转为文本。
     *
     * @param query            用户查询文本
     * @param researchGroupId  研究组 ID
     * @param maxResults       最大返回结果数
     * @param minScore         最低相似度阈值
     * @param enableRerank     是否启用 LLM rerank 精排
     * @return 格式化后的检索结果文本
     */
    public String retrieveLiteratureAndFormat(String query, String researchGroupId,
                                              int maxResults, double minScore,
                                              boolean enableRerank) {
        return formatRetrievalHits(retrieveLiteratureHits(query, researchGroupId, maxResults, minScore, enableRerank));
    }

    /**
     * 文献检索的核心方法 —— 编排 dense + sparse 双通道检索、RRF 融合、LLM rerank 全流程。
     *
     * <p>流程概览：
     * <ol>
     *   <li>放大候选数量（3 倍），分别执行 dense 和 sparse 检索</li>
     *   <li>通过 RRF 算法将两路结果融合为统一排名</li>
     *   <li>若启用 rerank 且候选数超过需求，则调用 LLM 精排截断</li>
     *   <li>否则直接按 RRF 分数截取 top-N</li>
     * </ol>
     *
     * @param query            用户查询文本
     * @param researchGroupId  研究组 ID，用于元数据过滤
     * @param maxResults       最终返回的最大结果数
     * @param minScore         向量相似度最低阈值
     * @param enableRerank     是否启用 LLM rerank
     * @return 检索命中列表，已按相关性排序；若无结果则返回空列表
     */
    public List<RetrievalHit> retrieveLiteratureHits(String query, String researchGroupId,
                                                     int maxResults, double minScore,
                                                     boolean enableRerank) {
        // 兼顾配置开关和调用方意愿，两者都允许才启用 rerank
        boolean rerank = enableRerank && runtimeProperties.isRerankEnabled();
        // 候选集放大：检索比最终需要的数量更多的候选，给 rerank 留出裁剪空间
        int candidateCount = Math.max(maxResults, maxResults * DEFAULT_CANDIDATE_MULTIPLIER);

        // 通道 1：Dense 向量检索（语义级匹配）
        List<EmbeddingMatch<TextSegment>> denseMatches = executeLiteratureSearch(query, researchGroupId, candidateCount, minScore);
        List<RetrievalHit> denseHits = denseMatches.stream()
                .map(this::toHit)
                .limit(candidateCount)
                .toList();

        // 通道 2：Sparse 稀疏检索（关键词级匹配），由配置开关控制是否启用
        List<RetrievalHit> sparseHits = runtimeProperties.isHybridSparseEnabled()
                ? pineconeSparseRetrievalService.searchLiterature(
                query, researchGroupId, Math.max(candidateCount, runtimeProperties.getSparseTopK()))
                : List.of();

        // RRF 融合：将两路结果按排名融合为统一分数
        List<RetrievalHit> mergedHits = mergeHybridHits(denseHits, sparseHits);
        if (mergedHits.isEmpty()) {
            return List.of();
        }

        // 精排阶段：当候选数多于需求数时，用 LLM 做语义重排序
        if (rerank && mergedHits.size() > maxResults) {
            return llmRerankService.rerankHits(query, mergedHits, maxResults).stream()
                    .map(this::toHit)
                    .toList();
        }
        // 无需 rerank 时直接截取 top-N
        return mergedHits.stream()
                .limit(Math.max(1, maxResults))
                .toList();
    }

    /**
     * 个人记忆检索并格式化（默认启用 rerank）。
     * 个人记忆与文献检索的区别在于过滤条件：按 userId 而非 researchGroupId。
     *
     * @param query       用户查询文本
     * @param userId      用户 ID，用于过滤该用户的个人记忆
     * @param maxResults  最大返回结果数
     * @param minScore    最低相似度阈值
     * @return 格式化后的检索结果文本
     */
    public String retrievePersonalMemoryAndFormat(String query, String userId,
                                                  int maxResults, double minScore) {
        return retrievePersonalMemoryAndFormat(query, userId, maxResults, minScore, true);
    }

    /**
     * 个人记忆检索并格式化（可控制是否启用 rerank）。
     *
     * @param query        用户查询文本
     * @param userId       用户 ID
     * @param maxResults   最大返回结果数
     * @param minScore     最低相似度阈值
     * @param enableRerank 是否启用 LLM rerank
     * @return 格式化后的检索结果文本
     */
    public String retrievePersonalMemoryAndFormat(String query, String userId,
                                                  int maxResults, double minScore,
                                                  boolean enableRerank) {
        return formatRetrievalHits(retrievePersonalMemoryHits(query, userId, maxResults, minScore, enableRerank));
    }

    /**
     * 个人记忆检索的核心方法 —— 仅涉及 dense 单通道，不含稀疏检索和 RRF 融合。
     * 个人记忆数据量相对较小，单通道 + rerank 已能满足质量要求。
     *
     * @param query        用户查询文本
     * @param userId       用户 ID，用于元数据过滤
     * @param maxResults   最终返回的最大结果数
     * @param minScore     向量相似度最低阈值
     * @param enableRerank 是否启用 LLM rerank
     * @return 检索命中列表；若无结果则返回空列表
     */
    public List<RetrievalHit> retrievePersonalMemoryHits(String query, String userId,
                                                         int maxResults, double minScore,
                                                         boolean enableRerank) {
        boolean rerank = enableRerank && runtimeProperties.isRerankEnabled();
        // 只有启用 rerank 时才放大候选集，否则直接取 maxResults 个即可
        int candidateCount = rerank ? maxResults * DEFAULT_CANDIDATE_MULTIPLIER : maxResults;
        List<EmbeddingMatch<TextSegment>> matches = executePersonalMemorySearch(query, userId, candidateCount, minScore);
        if (rerank && matches.size() > maxResults) {
            return llmRerankService.rerank(query, matches, maxResults).stream()
                    .map(this::toHit)
                    .toList();
        }
        return matches.stream()
                .limit(maxResults)
                .map(this::toHit)
                .toList();
    }

    /**
     * 执行文献的 dense 向量检索。
     * 流程：查询文本 -> EmbeddingModel 编码为向量 -> 在 EmbeddingStore 中按研究组过滤后做近邻搜索。
     *
     * <p>包含熔断器逻辑：一旦检索抛出异常，将 {@code embeddingStoreAvailable} 置为 false，
     * 后续请求会直接返回空列表，避免反复触发已知故障。
     *
     * @param query            查询文本
     * @param researchGroupId  研究组 ID，用于元数据过滤
     * @param maxResults       最大返回数
     * @param minScore         最低相似度阈值
     * @return 匹配结果列表，异常或熔断时返回空列表
     */
    private List<EmbeddingMatch<TextSegment>> executeLiteratureSearch(String query, String researchGroupId,
                                                                      int maxResults, double minScore) {
        // 熔断器：如果之前已经发现 EmbeddingStore 不可用，直接快速失败
        if (!embeddingStoreAvailable) {
            return List.of();
        }
        try {
            // 第一步：将自然语言查询编码为稠密向量
            Response<Embedding> embeddingResponse = embeddingModel.embed(query);
            // 第二步：构建带元数据过滤的向量搜索请求
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingResponse.content())
                    .maxResults(maxResults)
                    .minScore(minScore)
                    // 按 researchGroupId 过滤，确保只搜索当前研究组的文献
                    .filter(dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey("researchGroupId")
                            .isEqualTo(researchGroupId))
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
            log.info("Dense literature retrieval completed | matches={}", matches.size());
            return matches;
        } catch (Exception e) {
            // 触发熔断：标记 EmbeddingStore 不可用，后续请求将跳过检索
            embeddingStoreAvailable = false;
            log.warn("Dense literature retrieval failed, embedding store disabled for follow-up requests | error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 执行个人记忆的 dense 向量检索。
     * 与文献检索逻辑相同，区别仅在于元数据过滤条件为 userId。
     *
     * @param query      查询文本
     * @param userId     用户 ID，用于过滤该用户的个人记忆
     * @param maxResults 最大返回数
     * @param minScore   最低相似度阈值
     * @return 匹配结果列表，异常或熔断时返回空列表
     */
    private List<EmbeddingMatch<TextSegment>> executePersonalMemorySearch(String query, String userId,
                                                                          int maxResults, double minScore) {
        // 熔断器检查：与文献检索共用同一个标志位
        if (!embeddingStoreAvailable) {
            return List.of();
        }
        try {
            Response<Embedding> embeddingResponse = embeddingModel.embed(query);
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingResponse.content())
                    .maxResults(maxResults)
                    .minScore(minScore)
                    // 个人记忆按 userId 过滤，实现用户级别的数据隔离
                    .filter(dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey("userId")
                            .isEqualTo(userId))
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
            log.info("Personal memory retrieval completed | matches={}", matches.size());
            return matches;
        } catch (Exception e) {
            embeddingStoreAvailable = false;
            log.warn("Personal memory retrieval failed, embedding store disabled for follow-up requests | error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将 LLM rerank 后的结果格式化为可读文本。
     * 与 {@link #formatRetrievalHits} 的区别：这里展示的是 rerank 专项字段（rerankScore、reason）。
     *
     * @param rerankedResults LLM rerank 返回的结果列表
     * @return 格式化文本；若列表为空则返回提示信息
     */
    public String formatRerankedResults(List<RerankResult> rerankedResults) {
        if (rerankedResults == null || rerankedResults.isEmpty()) {
            return EMPTY_RESULT_MESSAGE;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Retrieved ").append(rerankedResults.size()).append(" evidence snippet(s) after reranking.\n\n");
        int index = 1;
        for (RerankResult result : rerankedResults) {
            sb.append("--- [Snippet ").append(index).append("] ---\n");
            sb.append("Source: ").append(result.getSource()).append("\n");
            // 同时展示 rerank 分数和原始向量分数，便于对比分析
            sb.append("Score: ").append(String.format("%.2f", result.getRerankScore()))
                    .append(" (dense=").append(String.format("%.2f", result.getVectorScore())).append(")\n");
            sb.append("Reason: ").append(result.getReason()).append("\n");
            sb.append("Content: ").append(result.getContent()).append("\n\n");
            index++;
        }
        return sb.toString();
    }

    /**
     * 将原始的 EmbeddingMatch 列表格式化为可读文本。
     * 内部先将 EmbeddingMatch 转为 RetrievalHit，再委托给 {@link #formatRetrievalHits}。
     *
     * @param matches 向量检索返回的匹配列表
     * @return 格式化文本；若列表为空则返回提示信息
     */
    public String formatRetrievalResults(List<EmbeddingMatch<TextSegment>> matches) {
        if (matches == null || matches.isEmpty()) {
            return EMPTY_RESULT_MESSAGE;
        }
        return formatRetrievalHits(matches.stream().map(this::toHit).toList());
    }

    /**
     * 将 RetrievalHit 列表格式化为人类可读的文本片段。
     * 这是所有格式化方法的最终汇聚点，输出格式直接用于 Prompt 拼接。
     *
     * <p>输出示例：
     * <pre>
     * Retrieved 3 evidence snippet(s).
     *
     * --- [Snippet 1] ---
     * Source: xxx.pdf
     * Section: Introduction
     * Page: 3
     * Chunk: 12
     * Score: 0.87
     * Dense score: 0.87
     * Sparse score: 0.45
     * Reason: hybrid_dense_sparse
     * Content: ...实际文本内容...
     * </pre>
     *
     * @param hits 检索命中列表
     * @return 格式化文本；若列表为空则返回提示信息
     */
    public String formatRetrievalHits(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return EMPTY_RESULT_MESSAGE;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Retrieved ").append(hits.size()).append(" evidence snippet(s).\n\n");
        int index = 1;
        for (RetrievalHit hit : hits) {
            sb.append("--- [Snippet ").append(index).append("] ---\n");
            // 优先展示 title，title 为空时退化为 source
            sb.append("Source: ").append(defaultString(hit.title, hit.source)).append("\n");
            if (hit.section != null && !hit.section.isBlank()) {
                sb.append("Section: ").append(hit.section).append("\n");
            }
            if (hit.sectionPath != null && !hit.sectionPath.isBlank()
                    && !hit.sectionPath.equals(hit.section)) {
                sb.append("Section path: ").append(hit.sectionPath).append("\n");
            }
            if (hit.pageNumber != null) {
                sb.append("Page: ").append(hit.pageNumber).append("\n");
            }
            if (hit.pageStart != null && hit.pageEnd != null && !hit.pageStart.equals(hit.pageEnd)) {
                sb.append("Pages: ").append(hit.pageStart).append("-").append(hit.pageEnd).append("\n");
            }
            if (hit.chunkIndex != null) {
                sb.append("Chunk: ").append(hit.chunkIndex).append("\n");
            }
            // 分数字段按需展示：混合检索时同时展示总分、dense 分、sparse 分
            if (hit.score != null) {
                sb.append("Score: ").append(String.format("%.2f", hit.score)).append("\n");
            }
            if (hit.vectorScore != null) {
                sb.append("Dense score: ").append(String.format("%.2f", hit.vectorScore)).append("\n");
            }
            if (hit.sparseScore != null) {
                sb.append("Sparse score: ").append(String.format("%.2f", hit.sparseScore)).append("\n");
            }
            if (hit.reason != null && !hit.reason.isBlank()) {
                sb.append("Reason: ").append(hit.reason).append("\n");
            }
            sb.append("Content: ").append(defaultString(hit.content, "")).append("\n\n");
            index++;
        }
        return sb.toString();
    }

    /**
     * 判断格式化后的检索结果是否实质为空。
     * 除了检查 null 和空字符串，还匹配常见的"无结果"提示文本。
     * 上层可据此决定是否将检索结果拼入 Prompt。
     *
     * @param formattedResult 格式化后的检索结果文本
     * @return true 表示结果为空或无实质内容
     */
    public boolean isResultEmpty(String formattedResult) {
        if (formattedResult == null || formattedResult.isBlank()) {
            return true;
        }
        String lower = formattedResult.toLowerCase();
        // 兼容不同格式化方法产生的空结果文案
        return formattedResult.contains("Retrieval results empty")
                || lower.contains("no relevant evidence")
                || lower.contains("no relevant content");
    }

    /**
     * 检索并 rerank，返回结构化的 RerankResult 列表（非格式化文本）。
     * 适用于需要对 rerank 结果做进一步处理（如展示 reason、分数分析）的场景。
     *
     * @param query            查询文本
     * @param researchGroupId  研究组 ID
     * @param maxResults       最终返回的最大结果数
     * @param minScore         最低相似度阈值
     * @return LLM rerank 后的结果列表
     */
    public List<RerankResult> retrieveAndRerank(String query, String researchGroupId,
                                                int maxResults, double minScore) {
        // 注意：这里传入 enableRerank=false，因为本方法自己会单独调用 rerank
        List<RetrievalHit> candidates = retrieveLiteratureHits(
                query,
                researchGroupId,
                Math.max(maxResults, maxResults * DEFAULT_CANDIDATE_MULTIPLIER),
                minScore,
                false);
        return llmRerankService.rerankHits(query, candidates, maxResults);
    }

    /**
     * 将 RerankResult 列表还原为原始的 EmbeddingMatch 列表。
     * 用于需要保留原始向量匹配信息（如 embeddingId）的下游场景。
     *
     * @param rerankedResults LLM rerank 后的结果列表
     * @return 原始 EmbeddingMatch 列表（已过滤掉 null）
     */
    public List<EmbeddingMatch<TextSegment>> convertToMatches(List<RerankResult> rerankedResults) {
        return rerankedResults.stream()
                .map(RerankResult::getOriginalMatch)
                .filter(match -> match != null)
                .collect(Collectors.toList());
    }

    /**
     * 使用 RRF（Reciprocal Rank Fusion）算法融合 dense 和 sparse 两路检索结果。
     *
     * <p>RRF 原理：对于排名第 i 的文档，其 RRF 贡献分数 = 1 / (k + i + 1)。
     * 同一文档如果在两路中都出现，分数会累加，从而自然地提升"双通道都认可"的文档排名。
     * 使用 LinkedHashMap 保持插入顺序，便于后续按 RRF 分数重排。
     *
     * @param denseHits  dense 向量检索结果
     * @param sparseHits sparse 稀疏检索结果
     * @return 融合后按 RRF 分数降序排列的结果列表
     */
    private List<RetrievalHit> mergeHybridHits(List<RetrievalHit> denseHits, List<RetrievalHit> sparseHits) {
        Map<String, RetrievalHit> merged = new LinkedHashMap<>();
        applyRrf(merged, denseHits, true);
        applyRrf(merged, sparseHits, false);
        // 先按 RRF 融合分数降序排列，分数相同时按 documentId 排序保证稳定性
        return merged.values().stream()
                .sorted(Comparator.comparing((RetrievalHit hit) -> hit.score, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(hit -> hit.documentId == null ? "" : hit.documentId))
                .toList();
    }

    /**
     * 将一路检索结果的 RRF 贡献分数累加到合并表中。
     *
     * <p>RRF 公式：contribution = 1 / (k + rank)，其中 rank = i + 1（从 1 开始计数）。
     * k=60 是论文推荐值，能有效平衡头部和尾部文档的权重差异。
     *
     * @param merged 已融合的结果映射表（key 为 hitKey 生成的唯一标识）
     * @param hits   当前通道的检索结果
     * @param dense  true 表示来自 dense 通道，false 表示来自 sparse 通道
     */
    private void applyRrf(Map<String, RetrievalHit> merged, List<RetrievalHit> hits, boolean dense) {
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit incoming = hits.get(i);
            String key = hitKey(incoming);
            RetrievalHit existing = merged.get(key);
            // RRF 核心公式：排名越靠前，贡献越大；k 值防止头部权重过高
            double fusedContribution = 1.0 / (RRF_K + i + 1.0);
            if (existing == null) {
                // 该文档首次出现，创建副本并初始化 RRF 分数
                RetrievalHit copy = copyHit(incoming);
                copy.score = fusedContribution;
                copy.reason = dense ? "dense_vector" : "sparse_vector";
                if (dense) {
                    copy.vectorScore = incoming.vectorScore != null ? incoming.vectorScore : incoming.score;
                } else {
                    copy.sparseScore = incoming.sparseScore != null ? incoming.sparseScore : incoming.score;
                }
                merged.put(key, copy);
                continue;
            }

            // 该文档已在另一通道出现过，累加 RRF 分数
            existing.score = (existing.score == null ? 0.0 : existing.score) + fusedContribution;
            if (dense) {
                // dense 通道补充：保留向量分数，并用更完整的元数据填充空字段
                existing.vectorScore = incoming.vectorScore != null ? incoming.vectorScore : incoming.score;
                existing.content = defaultString(existing.content, incoming.content);
                existing.source = defaultString(existing.source, incoming.source);
                existing.title = defaultString(existing.title, incoming.title);
                existing.section = defaultString(existing.section, incoming.section);
                existing.sectionPath = defaultString(existing.sectionPath, incoming.sectionPath);
            } else {
                // sparse 通道补充：保留稀疏分数，仅在内容为空时才覆盖
                existing.sparseScore = incoming.sparseScore != null ? incoming.sparseScore : incoming.score;
                if (existing.content == null || existing.content.isBlank()) {
                    existing.content = incoming.content;
                }
            }
            // 根据是否同时拥有 dense 和 sparse 分数来更新 reason 标记
            existing.reason = existing.vectorScore != null && existing.sparseScore != null
                    ? "hybrid_dense_sparse"
                    : dense ? "dense_vector" : "sparse_vector";
            // 合并元数据（sparse 通道可能携带额外字段）
            existing.metadata.putAll(incoming.metadata);
        }
    }

    /**
     * 生成检索命中的唯一标识键，用于 RRF 融合时判断是否为同一文档片段。
     * 键格式：documentId::pageNumber::chunkIndex
     *
     * @param hit 检索命中
     * @return 唯一标识字符串
     */
    private String hitKey(RetrievalHit hit) {
        if (hit.chunkId != null && !hit.chunkId.isBlank()) {
            return hit.chunkId;
        }
        return defaultString(hit.documentId, "unknown")
                + "::" + (hit.pageNumber == null ? "?" : hit.pageNumber)
                + "::" + (hit.chunkIndex == null ? "?" : hit.chunkIndex);
    }

    /**
     * 将 LLM RerankResult 转换为 RetrievalHit。
     * 优先使用 RerankResult 内部已有的 RetrievalHit（信息更完整），
     * 否则从原始 EmbeddingMatch 重建。
     *
     * @param result LLM rerank 返回的单条结果
     * @return 转换后的 RetrievalHit，分数和 reason 已更新为 rerank 后的值
     */
    private RetrievalHit toHit(RerankResult result) {
        if (result.getRetrievalHit() != null) {
            // RerankResult 内部已持有完整的 RetrievalHit，直接深拷贝后更新分数
            RetrievalHit hit = copyHit(result.getRetrievalHit());
            hit.score = result.getRerankScore();
            hit.reason = result.getReason();
            return hit;
        }
        // 回退路径：从原始 EmbeddingMatch 重建 hit，再覆写 rerank 分数
        RetrievalHit hit = toHit(result.getOriginalMatch());
        hit.score = result.getRerankScore();
        hit.vectorScore = result.getVectorScore();
        hit.reason = result.getReason();
        return hit;
    }

    /**
     * 将 langchain4j 的 EmbeddingMatch 转换为业务层的 RetrievalHit。
     * 从 TextSegment 的元数据中提取 source、title、section 等结构化字段。
     *
     * @param match 向量检索返回的单条匹配结果
     * @return 填充了元数据字段的 RetrievalHit
     */
    private RetrievalHit toHit(EmbeddingMatch<TextSegment> match) {
        RetrievalHit hit = new RetrievalHit();
        if (match == null) {
            return hit;
        }
        TextSegment segment = match.embedded();
        hit.score = match.score();
        hit.vectorScore = match.score(); // 初始时 score 和 vectorScore 相同
        hit.reason = "dense_vector";     // 标记来源为 dense 向量检索
        hit.embeddingId = match.embeddingId();
        if (segment == null) {
            return hit;
        }

        // 从 TextSegment 中提取文本内容和元数据
        hit.content = segment.text();
        if (segment.metadata() != null) {
            // 复制元数据到独立 Map，避免持有 langchain4j 内部对象的引用
            hit.metadata = new LinkedHashMap<>(segment.metadata().toMap());
            hit.source = metadataString(hit, "source");
            // title 优先，source 作为 fallback
            hit.title = defaultString(metadataString(hit, "title"), hit.source);
            hit.section = metadataString(hit, "section");
            hit.sectionPath = metadataString(hit, "sectionPath");
            hit.documentId = metadataString(hit, "documentId");
            hit.chunkId = metadataString(hit, "chunkId");
            hit.sectionId = metadataString(hit, "sectionId");
            hit.documentHash = metadataString(hit, "documentHash");
            hit.sourcePath = metadataString(hit, "sourcePath");
            hit.pageNumber = metadataInteger(hit, "pageNumber");
            hit.pageStart = metadataInteger(hit, "pageStart");
            hit.pageEnd = metadataInteger(hit, "pageEnd");
            hit.chunkIndex = metadataInteger(hit, "chunkIndex");
        }
        return hit;
    }

    /**
     * 深拷贝 RetrievalHit 对象。
     * 在 RRF 融合过程中需要拷贝，因为后续会修改 hit 的分数和 reason 字段，
     * 直接修改原始对象会导致检索通道间的数据污染。
     *
     * @param source 源对象
     * @return 深拷贝后的新对象
     */
    private RetrievalHit copyHit(RetrievalHit source) {
        RetrievalHit copy = new RetrievalHit();
        copy.content = source.content;
        copy.source = source.source;
        copy.title = source.title;
        copy.section = source.section;
        copy.sectionPath = source.sectionPath;
        copy.documentId = source.documentId;
        copy.chunkId = source.chunkId;
        copy.sectionId = source.sectionId;
        copy.documentHash = source.documentHash;
        copy.sourcePath = source.sourcePath;
        copy.pageNumber = source.pageNumber;
        copy.pageStart = source.pageStart;
        copy.pageEnd = source.pageEnd;
        copy.chunkIndex = source.chunkIndex;
        copy.score = source.score;
        copy.vectorScore = source.vectorScore;
        copy.sparseScore = source.sparseScore;
        copy.reason = source.reason;
        copy.embeddingId = source.embeddingId;
        copy.metadata.putAll(source.metadata);
        return copy;
    }

    /**
     * 从 RetrievalHit 的元数据 Map 中安全提取字符串值。
     *
     * @param hit 检索命中
     * @param key 元数据键名
     * @return 对应的字符串值，不存在时返回 null
     */
    private String metadataString(RetrievalHit hit, String key) {
        Object value = hit.metadata.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 从 RetrievalHit 的元数据 Map 中安全提取整数值。
     * 兼容 Number 类型和字符串类型的值，解析失败时返回 null。
     *
     * @param hit 检索命中
     * @param key 元数据键名
     * @return 对应的整数值，不存在或无法解析时返回 null
     */
    private Integer metadataInteger(RetrievalHit hit, String key) {
        Object value = hit.metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 字符串空值安全工具方法：value 为 null 或空白时返回 fallback。
     *
     * @param value    原始值
     * @param fallback 值为空时的替代值
     * @return 非空非空白的值
     */
    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
