package com.team.rag.service;

import com.team.rag.agent.runtime.MemoryRecallRecord;
import com.team.rag.agent.retrieval.RetrievalContext;
import com.team.rag.monitoring.AgentBusinessMetrics;
import com.team.rag.cache.LlmResponseCacheService;
import com.team.rag.config.AiConfig;
import com.team.rag.config.RagRuntimeProperties;
import com.team.rag.config.RedisChatMemoryStore;
import com.team.rag.entity.CragResult;
import com.team.rag.entity.RetrievalHit;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 文献对话服务（Literature Dialogue Service）。
 *
 * <p>职责：实现 Quick QA 的生成与 RAG 链路。意图与策略只由上游
 * ExecutionDecisionService 决定，本服务不再进行第二次路由。</p>
 *
 * <h3>设计思想：</h3>
 * <p>采用"五层检索链"架构，每一层都有明确的职责：</p>
 * <pre>
 * Layer 1: 查询改写（AcademicQueryRewriter）
 *     └── 结合对话历史，将口语化查询改写为学术化查询
 *
 * Layer 2: 混合召回（RetrievalFormattingService）
 *     ├── 文献检索（researchGroupId 过滤）
 *     └── 个人记忆检索（userId 过滤）
 *
 * Layer 3: CRAG 判断（CragJudgeService）
 *     ├── SUFFICIENT → 正常生成
 *     ├── PARTIAL → 带前缀生成（标注信息可能不完整）
 *     └── IRRELEVANT → 返回超出知识范围提示
 *
 * Layer 4: 受约束生成（ConstrainedGenerationAssistant）
 *     └── 强制引用检索结果，防止 LLM 幻觉
 * </pre>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * QuickQaExecutionService（统一运行入口）
 *     └── LiteratureDialogueService（本类：快速问答）
 *         ├── AcademicQueryRewriter（查询改写）
 *         ├── RetrievalFormattingService（检索格式化）
 *         ├── CragJudgeService（CRAG 判断）
 *         └── ConstrainedGenerationAssistant（受约束生成）
 * </pre>
 */
@Service
public class LiteratureDialogueService {

    private static final Logger log = LoggerFactory.getLogger(LiteratureDialogueService.class);

    /** 文献检索最大返回数 */
    private static final int MAX_LITERATURE_RESULTS = 5;

    /** 个人记忆检索最大返回数 */
    private static final int MAX_MEMORY_RESULTS = 3;

    /** 最低相似度阈值 */
    private static final double MIN_SCORE = 0.3;

    /** 流式聊天模型（如通义千问 qwen-plus） */
    private final StreamingChatLanguageModel streamingChatModel;

    /** 嵌入模型（如 text-embedding-ada-002） */
    private final EmbeddingModel embeddingModel;

    /** 向量存储后端（Pinecone） */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 双引擎记忆管理器（结构化画像 + 语义记忆） */
    private final DualEngineMemoryManager memoryManager;

    /** AI 配置（ChatMemory 管理等） */
    private final AiConfig aiConfig;

    /** Redis 聊天记忆存储（短期记忆滑动窗口） */
    private final RedisChatMemoryStore chatMemoryStore;

    /** 检索格式化服务（将检索结果格式化为可读文本） */
    private final RetrievalFormattingService retrievalFormattingService;

    /** CRAG 判断服务（判断检索内容的相关性） */
    private final CragJudgeService cragJudgeService;

    /** 学术查询改写器（结合对话历史改写查询） */
    private final AcademicQueryRewriter queryRewriter;

    /** 运行时配置属性 */
    private final RagRuntimeProperties runtimeProperties;

    /** LLM 响应缓存服务 */
    private final LlmResponseCacheService cacheService;
    private final AgentBusinessMetrics businessMetrics;

    /** 检索线程池（用于并行检索文献和记忆） */
    private final ExecutorService retrievalExecutor;

    public LiteratureDialogueService(StreamingChatLanguageModel streamingChatModel,
                                     EmbeddingModel embeddingModel,
                                     EmbeddingStore<TextSegment> embeddingStore,
                                     DualEngineMemoryManager memoryManager,
                                     AiConfig aiConfig,
                                     RedisChatMemoryStore chatMemoryStore,
                                     RetrievalFormattingService retrievalFormattingService,
                                     CragJudgeService cragJudgeService,
                                     AcademicQueryRewriter queryRewriter,
                                     RagRuntimeProperties runtimeProperties,
                                     LlmResponseCacheService cacheService,
                                     AgentBusinessMetrics businessMetrics) {
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.memoryManager = memoryManager;
        this.aiConfig = aiConfig;
        this.chatMemoryStore = chatMemoryStore;
        this.retrievalFormattingService = retrievalFormattingService;
        this.cragJudgeService = cragJudgeService;
        this.queryRewriter = queryRewriter;
        this.runtimeProperties = runtimeProperties;
        this.cacheService = cacheService;
        this.businessMetrics = businessMetrics;
        // 初始化检索线程池：用于并行检索文献和记忆，减少总延迟
        // 使用守护线程，避免阻止 JVM 关闭
        this.retrievalExecutor = Executors.newFixedThreadPool(
                Math.max(2, runtimeProperties.getRetrievalExecutorThreads()),
                runnable -> {
                    Thread thread = new Thread(runnable, "rag-retrieval");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Unified pipeline RAG execution path.
     * This method intentionally skips legacy intent branching and executes retrieval QA directly.
     */
    public Flux<String> streamRagOnly(String userId, String researchGroupId, String conversationId,
                                      String message, MemoryRecallRecord recall) {
        return streamRagOnly(userId, researchGroupId, conversationId, message, recall, ignored -> null);
    }

    /** Executes Quick QA and exposes the exact retrieval result used for generation. */
    public Flux<String> streamRagOnly(String userId, String researchGroupId, String conversationId,
                                      String message, MemoryRecallRecord recall,
                                      Function<RetrievalContext, String> contextCompiler) {
        String safeMessage = message == null ? "" : message;
        String memoryId = buildMemoryId(userId, conversationId);
        log.info("Start RAG-only dialogue | userId={}, researchGroupId={}, conversationId={}, messageLength={}",
                userId, researchGroupId, conversationId, safeMessage.length());
        return handleRagSearch(userId, researchGroupId, memoryId, safeMessage, recall, contextCompiler);
    }

    /** Executes the already-routed chitchat path without a second intent model call. */
    public Flux<String> streamGeneralChat(String userId, String conversationId, String message) {
        String memoryId = buildMemoryId(userId, conversationId);
        String safeMessage = message == null ? "" : message;
        log.info("Start general chat | userId={}, conversationId={}, messageLength={}",
                userId, conversationId, safeMessage.length());
        return handleGeneralChat(memoryId, safeMessage);
    }

    /**
     * 处理通用对话（不走 RAG 流程）。
     *
     * <p>直接调用 GeneralChatAssistant，不检索知识库。</p>
     */
    private Flux<String> handleGeneralChat(String memoryId, String message) {
        ChatMemory chatMemory = aiConfig.getOrCreateChatMemory(memoryId, chatMemoryStore);
        GeneralChatAssistant assistant = AiServices.builder(GeneralChatAssistant.class)
                .streamingChatLanguageModel(streamingChatModel)
                .chatMemory(chatMemory)
                .build();
        return tokenStreamToFlux(assistant.chat(message), null);
    }

    /**
     * 处理学术问答（RAG 流程）。
     *
     * <p>完整 RAG 流程：</p>
     * <ol>
     *   <li>构建用户画像（结构化画像 + 对话摘要 + 记忆召回）</li>
     *   <li>查询改写（结合对话历史）</li>
     *   <li>并行检索文献和个人记忆</li>
     *   <li>CRAG 判断（检索内容相关性）</li>
     *   <li>受约束生成（强制引用检索结果）</li>
     * </ol>
     */
    private Flux<String> handleRagSearch(String userId, String researchGroupId, String memoryId,
                                         String message, MemoryRecallRecord recall,
                                         Function<RetrievalContext, String> contextCompiler) {
        // 构建用户画像：结构化画像 + 对话摘要 + 记忆召回
        String profileText = memoryManager.formatStructuredProfileAsText(userId);
        String conversationSummary = chatMemoryStore.getSummary(memoryId);
        profileText = mergeProfileWithSummary(profileText, conversationSummary);
        profileText = mergeProfileWithRecall(profileText, recall);

        // Layer 2: 查询改写（结合对话历史，将口语化查询改写为学术化查询）
        String effectiveQuery = resolveEffectiveQuery(memoryId, message, recall);

        // Layer 3: 并行检索文献和个人记忆
        long retrievalStartedAt = System.currentTimeMillis();
        RetrievalBundle retrievalBundle = retrieveContexts(effectiveQuery, researchGroupId, userId);
        String literatureContext = retrievalBundle.literatureContext();
        String memoryContext = retrievalBundle.memoryContext();
        String combinedContext = combineContexts(literatureContext, memoryContext);

        log.info("Retrieval completed | effectiveQuery={}, literatureChars={}, memoryChars={}",
                preview(effectiveQuery), literatureContext.length(), memoryContext.length());

        // Layer 4: CRAG 判断（检索内容相关性）
        CragResult cragResult = cragJudgeService.judge(message, combinedContext);
        log.info("CRAG result | judgment={}, reason={}", cragResult.getJudgment(), cragResult.getReason());

        RetrievalContext retrievalContext = new RetrievalContext();
        retrievalContext.query = message;
        retrievalContext.rewrittenQueries = List.of(effectiveQuery);
        retrievalContext.knowledgeSpaceId = researchGroupId;
        retrievalContext.hits = new java.util.ArrayList<>(retrievalBundle.literatureHits());
        retrievalContext.memoryHits = new java.util.ArrayList<>(retrievalBundle.memoryHits());
        retrievalContext.cragDecision = cragResult.getJudgment();
        retrievalContext.cragReason = cragResult.getReason();
        retrievalContext.retrievalLatencyMs = System.currentTimeMillis() - retrievalStartedAt;
        businessMetrics.retrieval(retrievalContext.cragDecision,
                retrievalContext.hits.size(), retrievalContext.retrievalLatencyMs);
        if (retrievalContext.hits.isEmpty()) {
            retrievalContext.diagnostics.add("No literature evidence matched the active knowledge space.");
        }
        String generationContext = combinedContext;
        if (contextCompiler != null) {
            String compiled = contextCompiler.apply(retrievalContext);
            if (compiled != null && !compiled.isBlank()) {
                generationContext = compiled;
            }
        }

        // 处理 IRRELEVANT 结果
        if (cragResult.isIrrelevant()) {
            if (!hasUsableContext(literatureContext, memoryContext, recall)) {
                log.warn("CRAG marked context as irrelevant and no usable retrieval context exists");
                return Flux.just(buildIrrelevantResponse(researchGroupId, recall));
            }
            // 降级为 PARTIAL：虽然 CRAG 判断为 IRRELEVANT，但有可用的检索结果
            log.warn("CRAG marked context as irrelevant, but retrieval/recall has usable evidence; downgrading to PARTIAL");
        }

        // 检查答案缓存
        String generationCacheKey = buildGenerationCacheKey(message, effectiveQuery, generationContext, profileText, cragResult);
        if (runtimeProperties.isAnswerCacheEnabled()) {
            String cached = cacheService.get("rag-answer-v1", generationCacheKey);
            if (cached != null && !cached.isBlank()) {
                log.info("RAG answer cache hit | query={}", preview(effectiveQuery));
                return Flux.just(cached);
            }
        }

        // Layer 5: 受约束生成（强制引用检索结果，防止 LLM 幻觉）
        ChatMemory chatMemory = aiConfig.getOrCreateChatMemory(memoryId, chatMemoryStore);
        ConstrainedGenerationAssistant assistant = AiServices.builder(ConstrainedGenerationAssistant.class)
                .streamingChatLanguageModel(streamingChatModel)
                .chatMemory(chatMemory)
                .build();
        TokenStream tokenStream = assistant.generateWithConstraints(message, generationContext, profileText);

        // 如果是 PARTIAL 或 IRRELEVANT，在回答前添加前缀提示
        if (cragResult.isPartial() || cragResult.isIrrelevant()) {
            String prefix = cragJudgeService.getPartialPrefix();
            return Flux.concat(
                    Flux.just(prefix),
                    tokenStreamToFlux(tokenStream, answer -> cacheFinalAnswer(generationCacheKey, prefix + answer)));
        }
        return tokenStreamToFlux(tokenStream, answer -> cacheFinalAnswer(generationCacheKey, answer));
    }

    /**
     * 检索文献和个人记忆。
     *
     * <p>支持并行检索模式：使用 CompletableFuture 同时检索文献和记忆，
     * 减少总延迟（从串行的 2 次 IO 变为并行的 1 次 IO）。</p>
     *
     * @param effectiveQuery 改写后的查询
     * @param researchGroupId 研究组标识
     * @param userId 用户标识
     * @return 检索结果（文献上下文 + 记忆上下文）
     */
    private RetrievalBundle retrieveContexts(String effectiveQuery, String researchGroupId, String userId) {
        boolean rerankEnabled = runtimeProperties.isRerankEnabled();
        if (runtimeProperties.isParallelRetrievalEnabled()) {
            // 并行检索：同时检索文献和记忆，减少总延迟
            CompletableFuture<List<RetrievalHit>> literatureFuture = CompletableFuture.supplyAsync(
                    () -> retrievalFormattingService.retrieveLiteratureHits(
                            effectiveQuery, researchGroupId, MAX_LITERATURE_RESULTS, MIN_SCORE, rerankEnabled),
                    retrievalExecutor);
            CompletableFuture<List<RetrievalHit>> memoryFuture = CompletableFuture.supplyAsync(
                    () -> retrievalFormattingService.retrievePersonalMemoryHits(
                            effectiveQuery, userId, MAX_MEMORY_RESULTS, MIN_SCORE, rerankEnabled),
                    retrievalExecutor);
            List<RetrievalHit> literatureHits = literatureFuture.join();
            List<RetrievalHit> memoryHits = memoryFuture.join();
            return new RetrievalBundle(
                    literatureHits,
                    memoryHits,
                    retrievalFormattingService.formatRetrievalHits(literatureHits),
                    retrievalFormattingService.formatRetrievalHits(memoryHits));
        }
        List<RetrievalHit> literatureHits = retrievalFormattingService.retrieveLiteratureHits(
                effectiveQuery, researchGroupId, MAX_LITERATURE_RESULTS, MIN_SCORE, rerankEnabled);
        List<RetrievalHit> memoryHits = retrievalFormattingService.retrievePersonalMemoryHits(
                effectiveQuery, userId, MAX_MEMORY_RESULTS, MIN_SCORE, rerankEnabled);
        return new RetrievalBundle(
                literatureHits,
                memoryHits,
                retrievalFormattingService.formatRetrievalHits(literatureHits),
                retrievalFormattingService.formatRetrievalHits(memoryHits));
    }

    /**
     * 解析有效查询（Layer 2: 查询改写）。
     *
     * <p>查询改写的目的是将口语化的用户查询转换为更适合向量检索的学术化查询。
     * 例如，用户说"那个 Transformer 的注意力机制是怎么回事"，
     * 改写后可能变成"Transformer self-attention mechanism"。</p>
     *
     * <p>改写优先级：</p>
     * <ol>
     *   <li>MemoryRecallRecord 中的 effectiveQuery（来自 Agent Runtime）</li>
     *   <li>AcademicQueryRewriter 改写（结合对话历史）</li>
     *   <li>原始消息（降级方案）</li>
     * </ol>
     */
    private String resolveEffectiveQuery(String memoryId, String message, MemoryRecallRecord recall) {
        // 优先使用 Agent Runtime 提供的有效查询
        if (recall != null && recall.effectiveQuery != null && !recall.effectiveQuery.isBlank()) {
            return recall.effectiveQuery;
        }
        // 查询改写功能未启用时，直接使用原始消息
        if (!runtimeProperties.isQueryRewriteEnabled()) {
            return message;
        }
        try {
            // 结合对话历史进行查询改写
            List<ChatMessage> history = chatMemoryStore.getMessages(memoryId);
            return queryRewriter.rewrite(history, message);
        } catch (Exception e) {
            // 改写失败时降级为原始消息
            log.warn("Local query rewrite failed, fallback to original message | error={}", e.getMessage());
            return message;
        }
    }

    /**
     * 缓存最终答案。
     */
    private void cacheFinalAnswer(String cacheKey, String answer) {
        if (!runtimeProperties.isAnswerCacheEnabled() || answer == null || answer.isBlank()) {
            return;
        }
        cacheService.put("rag-answer-v1", cacheKey, answer);
    }

    /**
     * 构建答案缓存 Key。
     *
     * <p>Key 由用户消息、改写后的查询、检索上下文、用户画像和 CRAG 判断结果组成，
     * 确保相同的输入产生相同的缓存 Key。</p>
     */
    private String buildGenerationCacheKey(String message, String effectiveQuery, String combinedContext,
                                           String profileText, CragResult cragResult) {
        return String.join("\n---\n",
                defaultString(message, ""),
                defaultString(effectiveQuery, ""),
                defaultString(combinedContext, ""),
                defaultString(profileText, ""),
                cragResult == null ? "" : defaultString(cragResult.getJudgment(), ""));
    }

    /**
     * 合并用户画像与对话摘要。
     */
    private String mergeProfileWithSummary(String profileText, String summary) {
        if (summary == null || summary.isBlank()) {
            return profileText;
        }
        String base = (profileText == null || profileText.isBlank()) ? "No structured user profile." : profileText;
        return base + "\n[Conversation Summary]\n" + summary;
    }

    /**
     * 合并用户画像与记忆召回记录。
     */
    private String mergeProfileWithRecall(String profileText, MemoryRecallRecord recall) {
        if (recall == null) {
            return profileText;
        }
        StringBuilder sb = new StringBuilder(profileText == null ? "" : profileText);
        if (recall.compactPromptContext != null && !recall.compactPromptContext.isBlank()) {
            sb.append("\n[Pipeline Memory Recall]\n");
            sb.append(recall.compactPromptContext).append("\n");
            if (recall.truncatedSections != null && !recall.truncatedSections.isEmpty()) {
                sb.append("Recall truncation: ").append(String.join(", ", recall.truncatedSections)).append("\n");
            }
            if (recall.queryRewriteApplied && recall.rewrittenQuery != null) {
                sb.append("Effective retrieval query: ").append(recall.rewrittenQuery).append("\n");
            }
            return sb.toString();
        }
        sb.append("\n[Pipeline Memory Recall]\n");
        if (recall.shortTermSummary != null && !recall.shortTermSummary.isBlank()) {
            sb.append("Short-term summary: ").append(recall.shortTermSummary).append("\n");
        }
        if (recall.semanticMemories != null && !recall.semanticMemories.isEmpty()) {
            sb.append("Semantic memories:\n");
            recall.semanticMemories.stream().limit(5).forEach(memory ->
                    sb.append("- ").append(memory.getOrDefault("content", memory.toString())).append("\n"));
        }
        if (recall.diagnostics != null && !recall.diagnostics.isEmpty()) {
            sb.append("Recall diagnostics: ").append(String.join("; ", recall.diagnostics)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 合并文献上下文和记忆上下文。
     */
    private String combineContexts(String literatureContext, String memoryContext) {
        StringBuilder sb = new StringBuilder();
        if (!retrievalFormattingService.isResultEmpty(literatureContext)) {
            sb.append(literatureContext);
        }
        if (!retrievalFormattingService.isResultEmpty(memoryContext)) {
            sb.append("\n[Personal Research Memory]\n");
            sb.append(memoryContext);
        }
        return sb.toString();
    }

    /**
     * 检查是否有可用的检索上下文。
     */
    private boolean hasUsableContext(String literatureContext, String memoryContext, MemoryRecallRecord recall) {
        boolean hasFormattedContext = !retrievalFormattingService.isResultEmpty(literatureContext)
                || !retrievalFormattingService.isResultEmpty(memoryContext);
        if (hasFormattedContext) {
            return true;
        }
        Object hits = recall == null || recall.projectContext == null
                ? null
                : recall.projectContext.get("topLiteratureHits");
        return hits instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 构建无关响应（包含诊断信息）。
     */
    private String buildIrrelevantResponse(String researchGroupId, MemoryRecallRecord recall) {
        String base = cragJudgeService.getIrrelevantResponse();
        StringBuilder diagnostics = new StringBuilder();
        diagnostics.append("\n\n[Retrieval diagnostics]\n")
                .append("- researchGroupId: ").append(researchGroupId).append("\n");
        if (recall != null && recall.effectiveQuery != null && !recall.effectiveQuery.isBlank()) {
            diagnostics.append("- effectiveQuery: ").append(recall.effectiveQuery).append("\n");
        }
        if (recall != null && recall.diagnostics != null && !recall.diagnostics.isEmpty()) {
            recall.diagnostics.stream().limit(4).forEach(item -> diagnostics.append("- ").append(item).append("\n"));
        } else {
            diagnostics.append("- No recall diagnostics were produced.\n");
        }
        diagnostics.append("- Try uploading the relevant paper, binding the correct Telegram researchGroupId, or lowering the retrieval threshold.");
        return base + diagnostics;
    }

    /**
     * 构建记忆 ID（用于短期记忆隔离）。
     *
     * <p>格式：userId:conversationId，确保每个对话有独立的短期记忆。</p>
     */
    public String buildMemoryId(String userId, String conversationId) {
        String safeUserId = userId == null || userId.isBlank() ? "user-001" : userId.trim();
        String safeConversationId = conversationId == null || conversationId.isBlank()
                ? safeUserId
                : conversationId.trim();
        return safeUserId + ":" + safeConversationId;
    }

    /**
     * 将 LangChain4j 的 TokenStream 转换为 Reactor 的 Flux&lt;String&gt;。
     *
     * <p>使用 Sinks.many().unicast() 作为桥接：
     * <ul>
     *   <li>onNext: 每收到一个 Token 就推送到 Flux</li>
     *   <li>onComplete: 流结束时执行回调（如缓存答案）</li>
     *   <li>onError: 传播错误</li>
     * </ul>
     */
    private Flux<String> tokenStreamToFlux(TokenStream tokenStream, java.util.function.Consumer<String> onCompletedText) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder fullAnswer = new StringBuilder();
        tokenStream
                .onNext(token -> {
                    fullAnswer.append(token);
                    sink.tryEmitNext(token);
                })
                .onComplete(response -> {
                    if (onCompletedText != null) {
                        onCompletedText.accept(fullAnswer.toString());
                    }
                    sink.tryEmitComplete();
                })
                .onError(error -> {
                    log.error("Streaming failed", error);
                    sink.tryEmitError(error);
                })
                .start();
        return sink.asFlux();
    }

    /**
     * 安全的字符串默认值。
     */
    private String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 预览字符串（超过 120 字符时截断）。
     */
    private String preview(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() > 120 ? value.substring(0, 120) + "..." : value;
    }

    /**
     * 检索结果（内部 record）。
     */
    private record RetrievalBundle(List<RetrievalHit> literatureHits,
                                   List<RetrievalHit> memoryHits,
                                   String literatureContext,
                                   String memoryContext) {
    }
}
