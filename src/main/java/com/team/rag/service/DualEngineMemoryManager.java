package com.team.rag.service;

import com.team.rag.memory.MemoryLifecycleStatus;
import dev.langchain4j.data.document.Metadata;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 双引擎记忆管理器 —— RAG 系统的核心记忆层。
 *
 * <h3>设计思想</h3>
 * 本类将用户记忆拆分为两个互补的"引擎"：
 * <ul>
 *   <li><b>结构化长期记忆（引擎一）</b>：以 Redis Hash 存储用户的画像字段（如姓名、偏好、角色等），
 *       读写速度快，适合存储 KV 形式的用户属性。当 Redis 不可用时自动降级到本地 ConcurrentHashMap。</li>
 *   <li><b>语义长期记忆（引擎二）</b>：将自然语言记忆文本向量化后存入 EmbeddingStore，
 *       支持基于语义相似度的模糊召回。通过 SHA-256 哈希实现去重，避免重复存储。</li>
 * </ul>
 *
 * <h3>在系统中的位置</h3>
 * 本类位于 service 层，被上层的 RAG 问答链、工作流引擎、用户画像服务等调用，
 * 是连接"对话层"与"持久化层"的桥梁。Spring 自动注入三个核心依赖后即可使用。
 *
 * <h3>降级策略</h3>
 * Redis 和 EmbeddingStore 采用可恢复熔断：失败后短暂降级，冷却窗口结束后自动半开探测，
 * 避免一次瞬时故障永久关闭记忆能力。
 */
@Service
public class DualEngineMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(DualEngineMemoryManager.class);

    /** 结构化画像在 Redis 中的 key 前缀，完整 key 为 "user:profile:struct:{userId}" */
    private static final String REDIS_PROFILE_KEY_PREFIX = "user:profile:struct:";

    /** 语义记忆去重哈希集合在 Redis 中的 key 前缀，完整 key 为 "user:memory:semantic:hash:{userId}" */
    private static final String REDIS_MEMORY_HASH_KEY_PREFIX = "user:memory:semantic:hash:";

    private static final String REDIS_MEMORY_INDEX_KEY_PREFIX = "user:memory:semantic:index:";

    private static final String REDIS_MEMORY_ENABLED_KEY_PREFIX = "user:memory:semantic:enabled:";

    private static final long BACKEND_RETRY_MILLIS = Duration.ofSeconds(30).toMillis();

    /** Spring 提供的 Redis 操作模板，用于结构化画像和去重哈希的读写 */
    private final StringRedisTemplate redisTemplate;

    /** LangChain4j 的嵌入模型，负责将文本转换为向量 */
    private final EmbeddingModel embeddingModel;

    /** 向量存储，负责持久化嵌入向量及其关联的文本段（引擎二的核心） */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** Redis 是否可用；失败后经过冷却窗口允许自动探测恢复。 */
    private volatile boolean redisAvailable = false;

    private final AtomicLong redisRetryAfter = new AtomicLong(0L);

    /** EmbeddingStore 是否可用；失败后经过冷却窗口允许自动探测恢复。 */
    private volatile boolean embeddingStoreAvailable = true;

    private final AtomicLong embeddingRetryAfter = new AtomicLong(0L);

    /** Redis 不可用时的本地降级存储：userId -> {field: value}，模拟 Redis Hash */
    private final Map<String, Map<String, String>> localProfileStore = new ConcurrentHashMap<>();

    /** Redis 不可用时的本地去重哈希集合：userId -> {memoryHash}，模拟 Redis Set */
    private final Map<String, Set<String>> localMemoryHashes = new ConcurrentHashMap<>();

    /** memoryId -> embeddingId fallback index, used for delete/confirm operations. */
    private final Map<String, Map<String, String>> localMemoryIndex = new ConcurrentHashMap<>();

    /** Per-user opt-in setting fallback. Missing means enabled for backward compatibility. */
    private final Map<String, Boolean> localLongTermMemoryEnabled = new ConcurrentHashMap<>();

    /**
     * 构造器，由 Spring 自动注入依赖。
     * 初始化完成后立即尝试 PING Redis，决定结构化记忆的存储后端。
     *
     * @param redisTemplate   Redis 操作模板
     * @param embeddingModel  嵌入模型，用于文本转向量
     * @param embeddingStore  向量持久化存储
     */
    public DualEngineMemoryManager(StringRedisTemplate redisTemplate,
                                   EmbeddingModel embeddingModel,
                                   EmbeddingStore<TextSegment> embeddingStore) {
        this.redisTemplate = redisTemplate;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        testRedisConnection(); // 启动时探活，决定是用 Redis 还是本地降级
    }

    /**
     * 探测 Redis 连接是否可用。
     * 发送 PING 命令，成功则标记 redisAvailable = true；
     * 失败则走本地 ConcurrentHashMap 降级，不影响服务启动。
     */
    private void testRedisConnection() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            markRedisAvailable();
            log.info("Structured memory uses Redis");
        } catch (Exception e) {
            markRedisUnavailable();
            // Redis 不可用不阻断启动，降级到本地存储
            log.warn("Structured memory uses local fallback: {}", e.getMessage());
        }
    }

    /**
     * 保存用户的结构化画像（引擎一）。
     * 将 KV 形式的用户属性（如 name=张三, role=研究员）写入 Redis Hash 或本地降级存储。
     * 写入前会清理空白 key/value，并对 key 和 value 做 trim 处理。
     *
     * @param userId       用户唯一标识
     * @param profileData  要保存的画像字段，如 {"name": "张三", "role": "研究员"}
     */
    public void saveStructuredProfile(String userId, Map<String, String> profileData) {
        // 参数校验：userId 和 profileData 都不能为空
        if (isBlank(userId) || profileData == null || profileData.isEmpty()) {
            return;
        }

        // 清理数据：去掉 key/value 为空白的条目，trim 首尾空格
        Map<String, String> cleaned = new LinkedHashMap<>();
        profileData.forEach((key, value) -> {
            if (!isBlank(key) && !isBlank(value)) {
                cleaned.put(key.trim(), value.trim());
            }
        });
        if (cleaned.isEmpty()) {
            return;
        }

        // 优先写 Redis；失败则熔断并降级到本地
        if (canAttemptRedis()) {
            try {
                // putAll 是原子操作，会覆盖已有的同名字段
                redisTemplate.opsForHash().putAll(REDIS_PROFILE_KEY_PREFIX + userId, cleaned);
                markRedisAvailable();
                return;
            } catch (Exception e) {
                markRedisUnavailable();
                log.warn("Failed to write structured profile to Redis: {}", e.getMessage());
            }
        }
        // 降级路径：写入本地 ConcurrentHashMap
        localProfileStore.computeIfAbsent(userId, key -> new ConcurrentHashMap<>()).putAll(cleaned);
    }

    /**
     * 读取用户的结构化画像（引擎一）。
     * 优先从 Redis Hash 读取，Redis 不可用时从本地降级存储读取。
     *
     * @param userId  用户唯一标识
     * @return 画像字段的 Map（key 和 value 均为 Object），可能为空 Map 但不会为 null
     */
    public Map<Object, Object> getStructuredProfile(String userId) {
        // 优先读 Redis
        if (canAttemptRedis()) {
            try {
                Map<Object, Object> result = redisTemplate.opsForHash().entries(REDIS_PROFILE_KEY_PREFIX + userId);
                markRedisAvailable();
                return result;
            } catch (Exception e) {
                markRedisUnavailable();
                log.warn("Failed to read structured profile from Redis: {}", e.getMessage());
            }
        }
        // 降级路径：从本地读取，转换为 Map<Object, Object> 以保持返回类型一致
        Map<String, String> local = localProfileStore.get(userId);
        Map<Object, Object> result = new HashMap<>();
        if (local != null) {
            local.forEach(result::put);
        }
        return result;
    }

    /**
     * 保存语义记忆的便捷方法（引擎二）。
     * 使用默认参数：类型为 "personal_memory"，可见性为 "private"，无关联的研究组/对话/工作流。
     *
     * @param userId      用户唯一标识
     * @param memoryText  要保存的记忆文本
     * @return 生成的记忆 ID，格式为 "mem_" + SHA-256 前 16 位
     */
    public String saveSemanticMemory(String userId, String memoryText) {
        // 委托给全参数版本，其余参数使用默认值
        return saveSemanticMemory(userId, memoryText, null, null, null, "personal_memory", "private");
    }

    /**
     * 保存语义记忆到向量存储（引擎二的核心方法）。
     *
     * <p>处理流程：
     * <ol>
     *   <li>参数校验 → 文本归一化 → 生成 SHA-256 哈希（去重用）</li>
     *   <li>检查是否为重复记忆（相同 userId + type + 内容的哈希）</li>
     *   <li>构建元数据（memoryId、类型、可见性、重要性评分等）</li>
     *   <li>调用 EmbeddingModel 将文本向量化，存入 EmbeddingStore</li>
     *   <li>记录哈希用于后续去重</li>
     * </ol>
     *
     * @param userId           用户唯一标识
     * @param memoryText       要保存的记忆文本
     * @param researchGroupId  关联的研究组 ID（可为 null，用于搜索时的上下文加权）
     * @param conversationId   关联的对话 ID（可为 null，用于搜索时的上下文加权）
     * @param sourceWorkflowId 来源工作流 ID（可为 null，用于追溯记忆来源）
     * @param memoryType       记忆类型，如 "user_preference"、"workflow_answer"、"research_question"
     * @param visibility       可见性，如 "private"、"shared"
     * @return 生成的记忆 ID；去重时返回已有 ID；失败时返回 "mem_failed_" 前缀的 ID
     */
    public String saveSemanticMemory(String userId,
                                     String memoryText,
                                     String researchGroupId,
                                     String conversationId,
                                     String sourceWorkflowId,
                                     String memoryType,
                                     String visibility) {
        return saveSemanticMemory(userId, memoryText, researchGroupId, conversationId, sourceWorkflowId,
                memoryType, visibility, 1.0, MemoryLifecycleStatus.ACTIVE);
    }

    public String saveSemanticMemory(String userId,
                                     String memoryText,
                                     String researchGroupId,
                                     String conversationId,
                                     String sourceWorkflowId,
                                     String memoryType,
                                     String visibility,
                                     double confidence,
                                     MemoryLifecycleStatus lifecycleStatus) {
        // ---- 参数校验 ----
        if (isBlank(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (isBlank(memoryText)) {
            throw new IllegalArgumentException("memoryText is required");
        }
        if (!isLongTermMemoryEnabled(userId)) {
            String fallbackId = "mem_optout_" + System.currentTimeMillis();
            log.debug("Long-term memory disabled by user | userId={}, memoryId={}", userId, fallbackId);
            return fallbackId;
        }
        if (!canAttemptEmbeddingStore()) {
            String fallbackId = "mem_disabled_" + System.currentTimeMillis();
            log.debug("Embedding store disabled, skip semantic memory save | userId={}, memoryId={}", userId, fallbackId);
            return fallbackId;
        }

        // ---- 去重逻辑 ----
        // 归一化：去首尾空格，合并连续空白为单个空格，确保相同语义的文本生成相同哈希
        String normalized = normalizeMemoryText(memoryText);
        String safeType = defaultString(memoryType, "personal_memory");
        String safeVisibility = defaultString(visibility, "private");
        MemoryLifecycleStatus safeStatus = lifecycleStatus == null ? MemoryLifecycleStatus.ACTIVE : lifecycleStatus;
        // 用 userId + type + 归一化内容 生成哈希，保证同一用户的同一类型相同内容不会重复存储
        String hash = sha256(userId + "|" + safeType + "|" + normalized);
        String memoryId = "mem_" + hash.substring(0, 16); // 取前 16 位作为可读 ID
        if (isDuplicateMemory(userId, hash)) {
            log.info("Semantic memory skipped as duplicate | userId={}, memoryId={}", userId, memoryId);
            return memoryId;
        }

        // ---- 构建元数据并写入向量存储 ----
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("memoryId", memoryId);
            metadata.put("memoryHash", hash);        // 存储哈希，方便后续去重校验
            metadata.put("userId", userId);
            metadata.put("type", safeType);
            metadata.put("visibility", safeVisibility);
            metadata.put("createdAt", Instant.now().toString());
            metadata.put("lifecycleStatus", safeStatus.name());
            metadata.put("confidence", String.format(Locale.ROOT, "%.2f", clamp(confidence, 0.0, 1.0)));
            // 重要性评分会影响搜索时的重排序权重
            metadata.put("importanceScore", String.format(Locale.ROOT, "%.2f", estimateImportance(safeType, normalized)));
            // 以下三个字段仅在非空时写入，避免 null 值污染元数据
            putIfPresent(metadata, "researchGroupId", researchGroupId);
            putIfPresent(metadata, "conversationId", conversationId);
            putIfPresent(metadata, "sourceWorkflowId", sourceWorkflowId);

            // 将文本封装为 TextSegment（含元数据），调用嵌入模型生成向量
            TextSegment segment = TextSegment.from(normalized, Metadata.from(metadata));
            Response<Embedding> embeddingResponse = embeddingModel.embed(segment);
            // 向量和原文一起存入 EmbeddingStore，后续可通过向量相似度检索
            String embeddingId = embeddingStore.add(embeddingResponse.content(), segment);
            rememberMemoryHash(userId, hash); // 记录哈希，用于未来去重
            rememberMemoryIndex(userId, memoryId, embeddingId);
            markEmbeddingStoreAvailable();
            log.info("Semantic memory saved | userId={}, memoryId={}, type={}, status={}",
                    userId, memoryId, safeType, safeStatus);
            return memoryId;
        } catch (Exception e) {
            markEmbeddingStoreUnavailable();
            log.warn("Semantic memory save failed, backend cooling down | userId={}, error={}", userId, e.getMessage());
            return "mem_failed_" + System.currentTimeMillis();
        }
    }

    /**
     * 将用户的结构化画像格式化为可读文本，便于注入到 LLM 的 system prompt 中。
     * 输出格式如：
     * <pre>
     * [Structured User Profile]
     * - name: 张三
     * - role: 研究员
     * </pre>
     *
     * @param userId  用户唯一标识
     * @return 格式化的画像文本；无画像时返回 "No structured user profile."
     */
    public String formatStructuredProfileAsText(String userId) {
        Map<Object, Object> profile = getStructuredProfile(userId);
        if (profile.isEmpty()) {
            return "No structured user profile.";
        }
        StringBuilder sb = new StringBuilder("[Structured User Profile]\n");
        profile.forEach((key, value) -> sb.append("- ").append(key).append(": ").append(value).append("\n"));
        return sb.toString();
    }

    /**
     * 获取用户的语义记忆（便捷方法）。
     * 以 "user memory" 作为通用查询词，返回最近的语义记忆列表。
     *
     * @param userId     用户唯一标识
     * @param maxResults 最多返回的记忆条数
     * @return 记忆列表，每条包含 content、score、metadata 等字段
     */
    public List<Map<String, Object>> getSemanticMemories(String userId, int maxResults) {
        return searchSemanticMemories(userId, "user memory", maxResults, 0.0);
    }

    /**
     * 按查询文本搜索用户的语义记忆（简化版，无研究组/对话上下文）。
     *
     * @param userId      用户唯一标识
     * @param query       查询文本，用于生成查询向量
     * @param maxResults  最多返回的记忆条数
     * @param minScore    最低向量相似度阈值（0.0~1.0），低于此值的结果会被过滤
     * @return 按重排序分数降序排列的记忆列表
     */
    public List<Map<String, Object>> searchSemanticMemories(String userId, String query, int maxResults, double minScore) {
        return searchSemanticMemories(userId, null, null, query, maxResults, minScore);
    }

    /**
     * 搜索用户的语义记忆（引擎二的核心检索方法）。
     *
     * <p>检索流程：
     * <ol>
     *   <li>将查询文本向量化</li>
     *   <li>从 EmbeddingStore 中检索最相似的 maxResults*3 条候选（多取以保证重排序后仍有足够结果）</li>
     *   <li>对每条候选计算重排序分数：向量相似度 + 上下文加权 + 时效加权 + 重要性加权</li>
     *   <li>按重排序分数降序排列，截取前 maxResults 条返回</li>
     * </ol>
     *
     * <p>重排序规则参见 {@link #adjustMemoryScore}。
     *
     * @param userId          用户唯一标识
     * @param researchGroupId 当前研究组 ID（可为 null），匹配的记忆会获得加分
     * @param conversationId  当前对话 ID（可为 null），匹配的记忆会获得加分
     * @param query           查询文本
     * @param maxResults      最终返回的最大记忆条数
     * @param minScore        最低向量相似度阈值
     * @return 按重排序分数降序排列的记忆列表；异常或无结果时返回空列表
     */
    public List<Map<String, Object>> searchSemanticMemories(String userId,
                                                            String researchGroupId,
                                                            String conversationId,
                                                            String query,
                                                            int maxResults,
                                                            double minScore) {
        return searchMemoriesByStatus(userId, researchGroupId, conversationId, query,
                maxResults, minScore, MemoryLifecycleStatus.ACTIVE, true);
    }

    public List<Map<String, Object>> getSemanticMemoryCandidates(String userId, int maxResults) {
        return searchMemoriesByStatus(userId, null, null,
                "research preference recent question answer", maxResults, 0.0,
                MemoryLifecycleStatus.CANDIDATE, false);
    }

    private List<Map<String, Object>> searchMemoriesByStatus(String userId,
                                                             String researchGroupId,
                                                             String conversationId,
                                                             String query,
                                                             int maxResults,
                                                             double minScore,
                                                             MemoryLifecycleStatus requiredStatus,
                                                             boolean respectUserSetting) {
        if (isBlank(userId)
                || (respectUserSetting && !isLongTermMemoryEnabled(userId))
                || !canAttemptEmbeddingStore()) {
            return List.of();
        }
        try {
            // 将查询文本向量化，用于与存储的向量做相似度比较
            String safeQuery = defaultString(query, "user memory");
            Response<Embedding> queryEmbeddingResponse = embeddingModel.embed(TextSegment.from(safeQuery));

            // 多取 3 倍候选，因为重排序后顺序会变化，需要足够的候选池
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbeddingResponse.content())
                    .maxResults(Math.max(1, maxResults) * 3)
                    .minScore(Math.max(0.0, minScore))
                    // Type is intentionally not filtered: preferences, questions and workflow answers
                    // are all semantic memories. Lifecycle filtering is done below for legacy compatibility.
                    .filter(metadataKey("userId").isEqualTo(userId))
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<Map<String, Object>> memories = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
                TextSegment segment = match.embedded();
                Map<String, Object> metadata = segment.metadata() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(segment.metadata().toMap());
                if (MemoryLifecycleStatus.from(metadata.get("lifecycleStatus")) != requiredStatus) {
                    continue;
                }
                // 原始向量分数 + 上下文/时效/重要性加权 = 最终排序分数
                double rankScore = adjustMemoryScore(match.score(), metadata, researchGroupId, conversationId);

                // 组装返回结构，同时保留原始向量分数供调用方参考
                Map<String, Object> memory = new LinkedHashMap<>();
                memory.put("content", segment.text());
                memory.put("score", rankScore);          // 兼容旧字段
                memory.put("rankScore", rankScore);      // 重排序后的综合分数
                memory.put("vectorScore", match.score()); // 原始向量相似度
                memory.put("embeddingId", match.embeddingId());
                memory.put("metadata", metadata);
                memories.add(memory);
            }

            markEmbeddingStoreAvailable();

            // 按重排序分数降序排列，取前 maxResults 条
            return memories.stream()
                    .sorted(Comparator.comparingDouble(item -> -asDouble(item.get("rankScore"), 0.0)))
                    .limit(Math.max(1, maxResults))
                    .toList();
        } catch (Exception e) {
            markEmbeddingStoreUnavailable();
            log.warn("Embedding store unavailable, semantic memory recall cooling down | userId={}, error={}",
                    userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    public boolean deleteSemanticMemory(String userId, String memoryId) {
        if (isBlank(userId) || isBlank(memoryId) || !canAttemptEmbeddingStore()) {
            return false;
        }
        Map<String, Object> memory = findMemory(userId, memoryId);
        String embeddingId = lookupMemoryIndex(userId, memoryId);
        if (isBlank(embeddingId) && memory != null) {
            embeddingId = String.valueOf(memory.getOrDefault("embeddingId", ""));
        }
        if (isBlank(embeddingId)) {
            return false;
        }
        try {
            embeddingStore.remove(embeddingId);
            removeMemoryIndex(userId, memoryId);
            if (memory != null && memory.get("metadata") instanceof Map<?, ?> metadata) {
                forgetMemoryHash(userId, optionalString(metadata.get("memoryHash")));
            }
            markEmbeddingStoreAvailable();
            return true;
        } catch (Exception e) {
            markEmbeddingStoreUnavailable();
            log.warn("Semantic memory delete failed | userId={}, memoryId={}, error={}",
                    userId, memoryId, e.getMessage());
            return false;
        }
    }

    public boolean confirmSemanticMemoryCandidate(String userId, String memoryId) {
        if (!isLongTermMemoryEnabled(userId)) {
            return false;
        }
        Map<String, Object> memory = findMemory(userId, memoryId);
        if (memory == null || !(memory.get("metadata") instanceof Map<?, ?> metadata)
                || MemoryLifecycleStatus.from(metadata.get("lifecycleStatus")) != MemoryLifecycleStatus.CANDIDATE) {
            return false;
        }
        String content = String.valueOf(memory.getOrDefault("content", ""));
        String type = defaultString(optionalString(metadata.get("type")), "personal_memory");
        String visibility = defaultString(optionalString(metadata.get("visibility")), "private");
        String researchGroupId = optionalString(metadata.get("researchGroupId"));
        String conversationId = optionalString(metadata.get("conversationId"));
        String workflowId = optionalString(metadata.get("sourceWorkflowId"));
        if (!deleteSemanticMemory(userId, memoryId)) {
            return false;
        }
        String confirmedId = saveSemanticMemory(userId, content, researchGroupId, conversationId,
                workflowId, type, visibility, 1.0, MemoryLifecycleStatus.ACTIVE);
        return !confirmedId.startsWith("mem_failed_") && !confirmedId.startsWith("mem_disabled_");
    }

    public boolean isLongTermMemoryEnabled(String userId) {
        if (isBlank(userId)) {
            return false;
        }
        if (canAttemptRedis()) {
            try {
                String value = redisTemplate.opsForValue().get(REDIS_MEMORY_ENABLED_KEY_PREFIX + userId);
                markRedisAvailable();
                return value == null
                        ? localLongTermMemoryEnabled.getOrDefault(userId, true)
                        : Boolean.parseBoolean(value);
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
        return localLongTermMemoryEnabled.getOrDefault(userId, true);
    }

    public void setLongTermMemoryEnabled(String userId, boolean enabled) {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        localLongTermMemoryEnabled.put(userId, enabled);
        if (canAttemptRedis()) {
            try {
                redisTemplate.opsForValue().set(REDIS_MEMORY_ENABLED_KEY_PREFIX + userId,
                        Boolean.toString(enabled));
                markRedisAvailable();
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
    }

    /**
     * 查询 Redis 当前是否可用。
     * 可用于外部组件判断结构化记忆的存储后端状态。
     *
     * @return true 表示 Redis 可用，false 表示已降级到本地存储
     */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }

    public boolean isEmbeddingStoreAvailable() {
        return embeddingStoreAvailable;
    }

    private Map<String, Object> findMemory(String userId, String memoryId) {
        for (MemoryLifecycleStatus status : List.of(MemoryLifecycleStatus.ACTIVE, MemoryLifecycleStatus.CANDIDATE)) {
            List<Map<String, Object>> values = searchMemoriesByStatus(userId, null, null,
                    "research preference recent question answer", 100, 0.0, status, false);
            for (Map<String, Object> value : values) {
                if (value.get("metadata") instanceof Map<?, ?> metadata
                        && memoryId.equals(String.valueOf(metadata.get("memoryId")))) {
                    return value;
                }
            }
        }
        return null;
    }

    private void rememberMemoryIndex(String userId, String memoryId, String embeddingId) {
        if (isBlank(embeddingId)) {
            return;
        }
        localMemoryIndex.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .put(memoryId, embeddingId);
        if (canAttemptRedis()) {
            try {
                redisTemplate.opsForHash().put(REDIS_MEMORY_INDEX_KEY_PREFIX + userId, memoryId, embeddingId);
                markRedisAvailable();
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
    }

    private String lookupMemoryIndex(String userId, String memoryId) {
        if (canAttemptRedis()) {
            try {
                Object value = redisTemplate.opsForHash().get(REDIS_MEMORY_INDEX_KEY_PREFIX + userId, memoryId);
                markRedisAvailable();
                if (value != null) {
                    return value.toString();
                }
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
        return localMemoryIndex.getOrDefault(userId, Map.of()).get(memoryId);
    }

    private void removeMemoryIndex(String userId, String memoryId) {
        Map<String, String> index = localMemoryIndex.get(userId);
        if (index != null) {
            index.remove(memoryId);
        }
        if (canAttemptRedis()) {
            try {
                redisTemplate.opsForHash().delete(REDIS_MEMORY_INDEX_KEY_PREFIX + userId, memoryId);
                markRedisAvailable();
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
    }

    private void forgetMemoryHash(String userId, String hash) {
        if (isBlank(hash)) {
            return;
        }
        Set<String> local = localMemoryHashes.get(userId);
        if (local != null) {
            local.remove(hash);
        }
        if (canAttemptRedis()) {
            try {
                redisTemplate.opsForSet().remove(REDIS_MEMORY_HASH_KEY_PREFIX + userId, hash);
                markRedisAvailable();
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
    }

    private boolean canAttemptRedis() {
        return redisAvailable || System.currentTimeMillis() >= redisRetryAfter.get();
    }

    private void markRedisAvailable() {
        redisAvailable = true;
        redisRetryAfter.set(0L);
    }

    private void markRedisUnavailable() {
        redisAvailable = false;
        redisRetryAfter.set(System.currentTimeMillis() + BACKEND_RETRY_MILLIS);
    }

    private boolean canAttemptEmbeddingStore() {
        return embeddingStoreAvailable || System.currentTimeMillis() >= embeddingRetryAfter.get();
    }

    private void markEmbeddingStoreAvailable() {
        embeddingStoreAvailable = true;
        embeddingRetryAfter.set(0L);
    }

    private void markEmbeddingStoreUnavailable() {
        embeddingStoreAvailable = false;
        embeddingRetryAfter.set(System.currentTimeMillis() + BACKEND_RETRY_MILLIS);
    }

    /**
     * 检查某条记忆是否已存在（去重）。
     * 通过比对记忆内容的 SHA-256 哈希值来判断，避免存储重复的语义记忆。
     *
     * @param userId  用户唯一标识
     * @param hash    记忆内容的 SHA-256 哈希
     * @return true 表示已存在（应跳过），false 表示是新记忆
     */
    private boolean isDuplicateMemory(String userId, String hash) {
        if (canAttemptRedis()) {
            try {
                // 使用 Redis Set 的 isMember 做 O(1) 去重查询
                boolean duplicate = Boolean.TRUE.equals(
                        redisTemplate.opsForSet().isMember(REDIS_MEMORY_HASH_KEY_PREFIX + userId, hash));
                markRedisAvailable();
                return duplicate || localMemoryHashes.getOrDefault(userId, Set.of()).contains(hash);
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
        // 降级路径：从本地 ConcurrentHashMap 的 Set 中查找
        return localMemoryHashes.getOrDefault(userId, Set.of()).contains(hash);
    }

    /**
     * 将记忆哈希记录到去重集合中，供后续 save 时做去重校验。
     *
     * @param userId  用户唯一标识
     * @param hash    记忆内容的 SHA-256 哈希
     */
    private void rememberMemoryHash(String userId, String hash) {
        localMemoryHashes.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(hash);
        if (canAttemptRedis()) {
            try {
                // 使用 Redis Set 存储哈希，天然去重
                redisTemplate.opsForSet().add(REDIS_MEMORY_HASH_KEY_PREFIX + userId, hash);
                markRedisAvailable();
                return;
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
    }

    /**
     * 根据记忆类型和内容估算重要性分数。
     *
     * <p>评分规则：
     * <ul>
     *   <li>user_preference（用户偏好）→ 基础 0.95，最高优先级</li>
     *   <li>workflow_answer（工作流回答）→ 基础 0.82</li>
     *   <li>research_question（研究问题）→ 基础 0.72</li>
     *   <li>其他类型 → 基础 0.60</li>
     * </ul>
     * 额外加分：
     * <ul>
     *   <li>内容超过 280 字符 → +0.04（长文本通常信息量更大）</li>
     *   <li>包含"preference"/"偏好"/"记住"关键词 → +0.03（用户明确表达的偏好更重要）</li>
     * </ul>
     * 最终分数封顶 0.99。
     *
     * @param memoryType  记忆类型
     * @param content     记忆文本内容
     * @return 重要性分数，范围 [0.60, 0.99]
     */
    private double estimateImportance(String memoryType, String content) {
        // 根据记忆类型设定基础分
        double base = switch (defaultString(memoryType, "personal_memory")) {
            case "user_preference" -> 0.95;   // 用户偏好权重最高
            case "workflow_answer" -> 0.82;   // 工作流产生的回答
            case "research_question" -> 0.72; // 研究性问题
            default -> 0.60;                  // 兜底
        };
        // 长文本加分：信息量大的记忆应该更受重视
        if (content != null && content.length() > 280) {
            base += 0.04;
        }
        // 关键词加分：用户明确表达的偏好/记忆意图
        if (content != null) {
            String lower = content.toLowerCase(Locale.ROOT);
            if (lower.contains("preference") || content.contains("偏好") || content.contains("记住")) {
                base += 0.03;
            }
        }
        return Math.min(0.99, base); // 封顶，避免超过 1.0
    }

    /**
     * 对向量检索的原始分数进行重排序调整。
     *
     * <p>调整规则（在原始向量相似度基础上叠加）：
     * <ul>
     *   <li><b>研究组匹配</b>：记忆的 researchGroupId 与当前查询的研究组一致 → +0.12（强加分）
     *       <br>不一致但记忆有研究组标记 → -0.05（惩罚，因为属于不同上下文）</li>
     *   <li><b>对话匹配</b>：记忆的 conversationId 与当前对话一致 → +0.08</li>
     *   <li><b>时效加分</b>：参见 {@link #recencyBonus}，1天内 +0.10，7天内 +0.06，30天内 +0.03</li>
     *   <li><b>重要性加权</b>：importanceScore * 0.08，重要性越高的记忆越容易被召回</li>
     * </ul>
     *
     * @param vectorScore     向量检索返回的原始相似度分数
     * @param metadata        记忆的元数据
     * @param researchGroupId 当前查询的研究组 ID（可为 null）
     * @param conversationId  当前查询的对话 ID（可为 null）
     * @return 调整后的综合排序分数
     */
    private double adjustMemoryScore(double vectorScore,
                                     Map<String, Object> metadata,
                                     String researchGroupId,
                                     String conversationId) {
        double score = vectorScore;
        String memoryResearchGroupId = stringValue(metadata.get("researchGroupId"));
        String memoryConversationId = stringValue(metadata.get("conversationId"));

        // 研究组上下文加权：同组加分，不同组惩罚
        if (!isBlank(researchGroupId)) {
            if (researchGroupId.equals(memoryResearchGroupId)) {
                score += 0.12; // 同研究组的记忆更相关
            } else if (!isBlank(memoryResearchGroupId)) {
                score -= 0.05; // 属于其他研究组的记忆略作惩罚
            }
        }
        // 对话上下文加权：同一对话内的记忆更相关
        if (!isBlank(conversationId) && conversationId.equals(memoryConversationId)) {
            score += 0.08;
        }
        // 时效加分：越新的记忆越重要
        score += recencyBonus(stringValue(metadata.get("createdAt")));
        // 重要性加权：存储时估算的重要性越高，召回时越容易排在前面
        score += asDouble(metadata.get("importanceScore"), 0.50) * 0.08;
        return score;
    }

    /**
     * 根据记忆的创建时间计算时效加分。
     * 越近期的记忆加分越多，鼓励召回最新的上下文。
     *
     * <p>加分梯度：
     * <ul>
     *   <li>1 天以内 → +0.10（当天的记忆，高度相关）</li>
     *   <li>7 天以内 → +0.06（本周记忆）</li>
     *   <li>30 天以内 → +0.03（本月记忆）</li>
     *   <li>超过 30 天 → +0.00（不加分）</li>
     * </ul>
     *
     * @param createdAt  ISO 8601 格式的创建时间字符串
     * @return 时效加分，范围 [0.0, 0.10]
     */
    private double recencyBonus(String createdAt) {
        if (isBlank(createdAt)) {
            return 0.0;
        }
        try {
            // 计算记忆的"年龄"（天数）
            long ageDays = Math.max(0, Duration.between(Instant.parse(createdAt), Instant.now()).toDays());
            if (ageDays <= 1) {
                return 0.10; // 1天内：高度相关
            }
            if (ageDays <= 7) {
                return 0.06; // 1周内：较相关
            }
            if (ageDays <= 30) {
                return 0.03; // 1月内：略微相关
            }
        } catch (Exception ignored) {
            // 时间解析失败不加分，不影响主流程
            return 0.0;
        }
        return 0.0; // 超过30天：不加分
    }

    /**
     * 仅在 value 非空时才放入 Map，避免 null 值污染元数据。
     *
     * @param metadata  目标 Map
     * @param key       键
     * @param value     值（为 null 或空白时跳过）
     */
    private void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (!isBlank(value)) {
            metadata.put(key, value.trim());
        }
    }

    /**
     * 归一化记忆文本：去首尾空格 + 合并连续空白为单个空格。
     * 目的：确保相同语义的文本在不同输入格式下生成相同的哈希值。
     *
     * @param value  原始文本
     * @return 归一化后的文本
     */
    private String normalizeMemoryText(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    /**
     * 计算字符串的 SHA-256 哈希值，用于语义记忆的去重。
     * 相同输入始终产生相同的 64 位十六进制字符串。
     *
     * @param value  待哈希的字符串
     * @return 64 位十六进制哈希字符串
     * @throws IllegalStateException 如果 JVM 不支持 SHA-256（极罕见）
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            // 将每个字节转为两位十六进制，拼接成完整哈希
            StringBuilder sb = new StringBuilder();
            for (byte item : bytes) {
                sb.append(String.format("%02x", item));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 安全地将 Object 转为 String，null 时返回 null。
     * 用于从 metadata Map 中提取值时避免 NPE。
     *
     * @param value  任意对象
     * @return 字符串形式，或 null
     */
    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 安全地将 Object 转为 double，转换失败时返回 fallback。
     * 支持 Number 类型直接转换，以及可解析为数字的字符串。
     *
     * @param value     任意对象
     * @param fallback  转换失败时的默认值
     * @return double 值
     */
    private double asDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String optionalString(Object value) {
        return value == null ? null : value.toString();
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /**
     * 字符串为空白时返回默认值，否则 trim 后返回。
     *
     * @param value     原始字符串
     * @param fallback  为空白时的替代值
     * @return 处理后的字符串
     */
    private String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    /**
     * 判断字符串是否为 null 或空白（空串、纯空格等）。
     *
     * @param value  待判断的字符串
     * @return true 表示为空白
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
