package com.team.rag.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.cache.LlmResponseCacheService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的聊天记忆持久化存储，同时实现了自动摘要和本地降级能力。
 *
 * <h3>核心职责</h3>
 * <ol>
 *   <li><b>消息持久化</b>：将会话窗口存入 Redis（key = {@code chat:memory:{memoryId}}），TTL 30 天。</li>
 *   <li><b>对话摘要</b>：当滑动窗口淘汰 ≥ 4 条旧消息时，异步调用 LLM 生成/合并摘要，
 *       存入 Redis（key = {@code chat:summary:{memoryId}}），TTL 90 天。</li>
 *   <li><b>降级容错</b>：Redis 不可用时自动回退到本地 {@link LinkedHashMap}（LRU，上限 1000 条），
 *       每 60 秒 PING 检测 Redis 是否恢复，恢复后自动将本地数据 flush 回 Redis。</li>
 * </ol>
 *
 * <h3>设计思想</h3>
 * <ul>
 *   <li><b>滑动窗口 + 摘要压缩</b>：LangChain4j 的 ChatMemory 只保留最近 N 条消息，
 *       淘汰的旧消息如果直接丢弃会丢失上下文；本类在淘汰时触发 LLM 摘要，
 *       让摘要随消息一起传递给模型，实现"长期记忆"效果。</li>
 *   <li><b>优雅降级</b>：Redis 故障不应导致整个聊天系统不可用，因此用本地 Map 兜底，
 *       并通过定期 PING 实现自动恢复。</li>
 *   <li><b>线程安全</b>：本地降级 Map 通过 {@code synchronized(fallbackLock)} 保护；
 *       Redis 可用性标志使用 {@code volatile} 保证可见性。</li>
 * </ul>
 *
 * <h3>在系统中的位置</h3>
 * <p>作为 Spring {@link Component} 注入到 LangChain4j 的 {@link dev.langchain4j.store.memory.chat.ChatMemoryStore}
 * 接口实现链中，供 {@code ChatMemoryProvider} 或 {@code AiService} 使用。
 * 上层通过 {@link com.team.rag.service.ConversationService} 间接调用本类。</p>
 *
 * @see ChatMemoryStore
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    // ==================== Redis Key 前缀 ====================
    /** 消息列表的 Redis key 前缀，完整格式为 chat:memory:{memoryId} */
    private static final String KEY_PREFIX = "chat:memory:";
    /** 对话摘要的 Redis key 前缀，完整格式为 chat:summary:{memoryId} */
    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";

    // ==================== 过期与容量限制 ====================
    private static final Duration MESSAGE_TTL = Duration.ofDays(30);
    private static final Duration SUMMARY_TTL = Duration.ofDays(90);
    /** 本地降级 Map 最多保留的 key 数量（LRU 淘汰），防止内存溢出 */
    private static final int MAX_FALLBACK_ENTRIES = 1000;
    /** 本地降级时单条 JSON 的最大字符数，超过则丢弃（防止大对话撑爆内存） */
    private static final int MAX_FALLBACK_JSON_CHARS = 200_000;
    /** 摘要文本的最大字符数，超过则截断，避免摘要本身过长 */
    private static final int MAX_SUMMARY_CHARS = 2000;

    // ==================== 摘要触发与超时 ====================
    /**
     * 触发摘要生成的最低淘汰消息数。
     * 只有当 ≥ 4 条旧消息被淘汰时才调用 LLM 生成摘要，
     * 避免频繁调用带来的延迟和成本开销。
     */
    private static final int SUMMARY_TRIGGER_DROPPED_MESSAGES = 4;
    /**
     * LLM 摘要生成的超时时间（毫秒）。
     * 设为 1.2 秒是为了在"摘要质量"和"响应延迟"之间取平衡——
     * 摘要生成是在 updateMessages 调用链中同步等待的，太长会拖慢用户请求。
     */
    private static final long SUMMARY_TIMEOUT_MS = 1200;
    /** Redis 健康检查的最小间隔（60 秒），避免每次请求都 PING 造成额外开销 */
    private static final long REDIS_CHECK_INTERVAL_MS = 60_000;

    // ==================== 核心依赖 ====================
    /** Spring Redis 客户端，所有 Redis 读写操作都通过它完成 */
    private final StringRedisTemplate redisTemplate;
    /** Jackson 序列化器，用于将 ChatMessage 列表 ↔ JSON 字符串互相转换 */
    private final ObjectMapper objectMapper;
    /** LangChain4j 的语言模型接口，用于异步生成对话摘要 */
    private final ChatLanguageModel chatModel;
    /** 对高频且确定性较强的摘要合并任务提供 Caffeine + Redis 两级缓存 */
    private final LlmResponseCacheService cacheService;

    // ==================== 本地降级存储 ====================
    /** 本地降级 Map 的锁对象，保护 localMessages 和 localSummaries 的并发访问 */
    private final Object fallbackLock = new Object();
    /**
     * 本地消息降级缓存（LRU 有序）。
     * Redis 不可用时，消息 JSON 暂存于此；Redis 恢复后 flush 回去。
     * 使用 LinkedHashMap(accessOrder=true) 实现 LRU 淘汰。
     */
    private final Map<String, String> localMessages = new LinkedHashMap<>();
    /** 本地摘要降级缓存，逻辑同 localMessages */
    private final Map<String, String> localSummaries = new LinkedHashMap<>();

    // ==================== Redis 健康状态 ====================
    /** Redis 当前是否可用，volatile 保证多线程间的可见性 */
    private volatile boolean redisAvailable = true;
    /** 上次执行 Redis PING 健康检查的时间戳（毫秒），用于控制检查频率 */
    private volatile long lastRedisCheckTime = 0;

    /**
     * 构造函数，由 Spring 自动注入依赖。
     *
     * @param redisTemplate Spring 提供的 Redis 操作模板，用于读写对话消息和摘要
     * @param chatModel     LangChain4j 的语言模型，用于在消息淘汰时异步生成对话摘要
     * @param cacheService  LLM Cache-Aside 服务，复用相同摘要合并结果
     */
    public RedisChatMemoryStore(StringRedisTemplate redisTemplate,
                                ChatLanguageModel chatModel,
                                LlmResponseCacheService cacheService) {
        this.redisTemplate = redisTemplate;
        this.chatModel = chatModel;
        this.cacheService = cacheService;
        // 手动创建 ObjectMapper 而非注入 Spring 的，因为序列化格式简单且无需自定义配置
        this.objectMapper = new ObjectMapper();
        log.info("RedisChatMemoryStore initialized");
    }

    /**
     * 从存储中读取指定会话的全部消息列表。
     *
     * <p>读取优先走 Redis；若 Redis 不可用则回退到本地降级 Map。
     * 返回空列表表示该会话尚无历史消息。</p>
     *
     * @param memoryId 会话唯一标识（通常由上层传入 UUID 或用户 ID + 会话 ID 的组合）
     * @return 该会话的全部 ChatMessage 列表，不存在时返回空列表（不会返回 null）
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        if (!isRedisAvailable()) {
            return getMessagesFromLocal(key);
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null || json.isBlank() ? new ArrayList<>() : deserializeMessages(json);
        } catch (RedisConnectionFailureException e) {
            // 连接失败是预期的降级场景，标记 Redis 不可用并回退到本地存储
            markRedisUnavailable();
            return getMessagesFromLocal(key);
        } catch (Exception e) {
            // 非连接类异常（如序列化错误）不触发降级，只记录日志并返回空
            log.warn("Failed to read chat memory | memoryId={}, error={}", memoryId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 更新指定会话的消息列表，同时处理摘要生成和降级逻辑。
     *
     * <p>这是本类最核心的方法，执行流程如下：</p>
     * <ol>
     *   <li>先读取存储中的旧消息，与新消息对比，计算被淘汰的旧消息（droppedMessages）。</li>
     *   <li>将新消息序列化为 JSON 并写入 Redis（带 24h TTL）。</li>
     *   <li>如果淘汰消息数 ≥ 4，异步调用 LLM 生成/合并摘要。</li>
     *   <li>若 Redis 写入失败，回退写入本地降级 Map。</li>
     * </ol>
     *
     * @param memoryId 会话唯一标识
     * @param messages 该会话最新的完整消息列表（已由上层的 ChatMemory 裁剪为滑动窗口大小）
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        String summaryKey = buildSummaryKey(memoryId);
        // 读取存储中的原始消息（用于和新消息对比，找出被淘汰的旧消息）
        List<ChatMessage> previousMessages = loadStoredMessagesRaw(key);
        // 计算被滑动窗口淘汰的旧消息，后续用于触发摘要生成
        List<ChatMessage> droppedMessages = computeDroppedMessages(previousMessages, messages);

        String json;
        try {
            json = serializeMessages(messages);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize chat memory | memoryId={}, error={}", memoryId, e.getMessage());
            return;
        }

        if (isRedisAvailable()) {
            try {
                // 写入 Redis 并设置 24h TTL，过期后自动清理
                redisTemplate.opsForValue().set(key, json, MESSAGE_TTL);
                // 摘要生成和存储也在 Redis 可用时一并处理
                maybeSummarizeAndStore(summaryKey, droppedMessages);
                return;
            } catch (RedisConnectionFailureException e) {
                markRedisUnavailable();
            } catch (Exception e) {
                log.warn("Failed to write chat memory to Redis | memoryId={}, error={}", memoryId, e.getMessage());
                markRedisUnavailable();
            }
        }

        // Redis 不可用时，降级写入本地 Map
        saveToLocal(key, json);
        maybeSummarizeAndStore(summaryKey, droppedMessages);
    }

    /**
     * 删除指定会话的全部消息和摘要（同时清理 Redis 和本地降级缓存）。
     *
     * <p>先清理本地缓存（加锁），再尝试清理 Redis。
     * 即使 Redis 删除失败也不抛异常，仅记录日志。</p>
     *
     * @param memoryId 会话唯一标识
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        String summaryKey = buildSummaryKey(memoryId);
        // 先清理本地缓存（加锁），再清理 Redis——即使 Redis 失败，本地也不会残留脏数据
        synchronized (fallbackLock) {
            localMessages.remove(key);
            localSummaries.remove(summaryKey);
        }
        if (isRedisAvailable()) {
            try {
                redisTemplate.delete(key);
                redisTemplate.delete(summaryKey);
            } catch (Exception e) {
                log.warn("Failed to delete chat memory | memoryId={}, error={}", memoryId, e.getMessage());
            }
        }
    }

    /**
     * 获取指定会话的对话摘要。
     * 摘要由 LLM 自动生成（当旧消息被淘汰时），用于给模型提供长期上下文。
     *
     * @param memoryId 会话唯一标识
     * @return 摘要文本，不存在时返回空字符串
     */
    public String getSummary(Object memoryId) {
        return getSummaryByKey(buildSummaryKey(memoryId));
    }

    /**
     * 检查指定会话是否有存储的消息。
     * 优先查 Redis，Redis 不可用时查本地降级缓存。
     *
     * @param memoryId 会话唯一标识
     * @return true 表示该会话有历史消息，false 表示不存在
     */
    public boolean exists(Object memoryId) {
        String key = buildKey(memoryId);
        if (isRedisAvailable()) {
            // Boolean.TRUE.equals 避免 hasKey 返回 null 时的 NPE
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        }
        synchronized (fallbackLock) {
            return localMessages.containsKey(key);
        }
    }

    /**
     * 获取指定会话当前存储的消息条数。
     * 注意：这会触发一次完整的反序列化，频繁调用时需注意性能。
     *
     * @param memoryId 会话唯一标识
     * @return 消息条数，会话不存在时返回 0
     */
    public long getMessageCount(Object memoryId) {
        return getMessages(memoryId).size();
    }

    /**
     * 刷新指定会话在 Redis 中的过期时间（重置为 24 小时）。
     * 典型场景：用户发送新消息时调用，防止活跃会话被过早清理。
     * Redis 不可用时静默跳过（本地缓存没有 TTL 机制）。
     *
     * @param memoryId 会话唯一标识
     */
    public void refreshTtl(Object memoryId) {
        if (!isRedisAvailable()) {
            return;
        }
        redisTemplate.expire(buildKey(memoryId), MESSAGE_TTL);
        redisTemplate.expire(buildSummaryKey(memoryId), SUMMARY_TTL);
    }

    /**
     * 获取当前存储模式的可读描述（用于监控或健康检查接口展示）。
     *
     * @return "Redis" 或 "Local Memory (Fallback)"
     */
    public String getStorageMode() {
        return isRedisAvailable() ? "Redis" : "Local Memory (Fallback)";
    }

    /**
     * 构建消息列表在 Redis 中的完整 key。
     * @param memoryId 会话标识
     * @return 格式为 chat:memory:{memoryId} 的 key
     */
    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }

    /**
     * 构建对话摘要在 Redis 中的完整 key。
     * @param memoryId 会话标识
     * @return 格式为 chat:summary:{memoryId} 的 key
     */
    private String buildSummaryKey(Object memoryId) {
        return SUMMARY_KEY_PREFIX + memoryId;
    }

    /**
     * 检测 Redis 当前是否可用，同时负责自动恢复。
     *
     * <p>核心逻辑：</p>
     * <ul>
     *   <li>如果 Redis 已标记为不可用，且距离上次检查不足 60 秒，直接返回 false（避免频繁 PING）。</li>
     *   <li>如果距离上次检查已超过 60 秒，执行一次 PING：
     *       <ul>
     *         <li>PING 成功：标记为可用；如果之前是不可用状态，触发 flushLocalFallbackToRedis() 把本地数据同步回 Redis。</li>
     *         <li>PING 失败：标记为不可用，记录检查时间。</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * @return true 表示 Redis 当前可用，false 表示不可用（调用方应走降级路径）
     */
    private boolean isRedisAvailable() {
        long now = System.currentTimeMillis();
        // Redis 已不可用 + 距上次检查不足 60 秒 → 直接返回 false，避免每次请求都 PING
        if (!redisAvailable && now - lastRedisCheckTime < REDIS_CHECK_INTERVAL_MS) {
            return false;
        }
        // 距上次检查已超过 60 秒 → 执行一次 PING 健康检查
        if (now - lastRedisCheckTime >= REDIS_CHECK_INTERVAL_MS) {
            try {
                redisTemplate.getConnectionFactory().getConnection().ping();
                boolean wasUnavailable = !redisAvailable;
                redisAvailable = true;
                lastRedisCheckTime = now;
                // 之前不可用而现在恢复了 → 把降级期间积累的本地数据 flush 回 Redis
                if (wasUnavailable) {
                    flushLocalFallbackToRedis();
                }
            } catch (Exception e) {
                // PING 失败 → 标记不可用，后续请求将走本地降级路径
                redisAvailable = false;
                lastRedisCheckTime = now;
            }
        }
        return redisAvailable;
    }

    /**
     * 将 Redis 标记为不可用，并记录当前时间戳（用于控制下次 PING 检查的间隔）。
     */
    private void markRedisUnavailable() {
        redisAvailable = false;
        lastRedisCheckTime = System.currentTimeMillis();
    }

    /**
     * 从本地降级缓存读取消息列表（加锁保证线程安全）。
     * @param key 完整的 Redis key（如 chat:memory:xxx）
     * @return 消息列表，不存在时返回空列表
     */
    private List<ChatMessage> getMessagesFromLocal(String key) {
        synchronized (fallbackLock) {
            String json = localMessages.get(key);
            return json == null || json.isBlank() ? new ArrayList<>() : deserializeMessages(json);
        }
    }

    /**
     * 读取存储中的原始消息列表（用于 updateMessages 中对比新旧消息、计算淘汰部分）。
     * 优先走 Redis，Redis 异常时回退到本地缓存。
     *
     * @param key 完整的 Redis key
     * @return 消息列表，不存在时返回空列表
     */
    private List<ChatMessage> loadStoredMessagesRaw(String key) {
        if (isRedisAvailable()) {
            try {
                String json = redisTemplate.opsForValue().get(key);
                return json == null || json.isBlank() ? new ArrayList<>() : deserializeMessages(json);
            } catch (Exception e) {
                // 这里捕获所有异常（不仅是连接失败），因为 loadStoredMessagesRaw 是内部辅助方法，
                // 任何 Redis 异常都应该触发降级，避免 updateMessages 因读取失败而中断
                markRedisUnavailable();
            }
        }
        return getMessagesFromLocal(key);
    }

    /**
     * 计算被滑动窗口淘汰的旧消息。
     *
     * <p>算法思路：在 previous 的末尾和 current 的开头之间寻找最大重叠区域，
     * 重叠部分之前的消息就是被淘汰的旧消息。</p>
     *
     * <p>示例：previous = [A, B, C, D, E]，current = [C, D, E, F, G]
     * 最大重叠 = [C, D, E]，被淘汰 = [A, B]</p>
     *
     * <p>从最大可能重叠长度向下搜索，找到第一个完全匹配的 k 值即为最大重叠。
     * 这样即使消息内容有重复也能正确处理。</p>
     *
     * @param previous 存储中的旧消息列表
     * @param current  上层传入的新消息列表（滑动窗口裁剪后）
     * @return 被淘汰的旧消息列表（保持原始顺序），无淘汰时返回空列表
     */
    private List<ChatMessage> computeDroppedMessages(List<ChatMessage> previous, List<ChatMessage> current) {
        if (previous == null || previous.isEmpty()) {
            return List.of();
        }
        // current 为空说明所有消息都被删除了，previous 全部算作淘汰
        if (current == null || current.isEmpty()) {
            return new ArrayList<>(previous);
        }
        // 最大可能重叠长度取两者中的较小值
        int maxOverlap = Math.min(previous.size(), current.size());
        int overlap = 0;
        // 从最大可能重叠向下搜索，找到第一个完全匹配的 k 值（贪心策略，保证找到最大重叠）
        for (int k = maxOverlap; k >= 0; k--) {
            boolean matched = true;
            // 检查 previous 末尾 k 条消息是否与 current 开头 k 条消息一一对应
            for (int i = 0; i < k; i++) {
                ChatMessage a = previous.get(previous.size() - k + i);
                ChatMessage b = current.get(i);
                if (!isSameMessage(a, b)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                overlap = k;
                break;
            }
        }
        // 重叠部分之前的消息就是被淘汰的旧消息
        int droppedSize = previous.size() - overlap;
        return droppedSize <= 0 ? List.of() : new ArrayList<>(previous.subList(0, droppedSize));
    }

    /**
     * 判断两条消息是否"相同"（类型相同 + 文本内容相同）。
     * 用于 computeDroppedMessages 中匹配新旧消息的重叠区域。
     */
    private boolean isSameMessage(ChatMessage a, ChatMessage b) {
        return a != null && b != null && a.type() == b.type() && getMessageText(a).equals(getMessageText(b));
    }

    /**
     * 提取消息的纯文本内容。
     * LangChain4j 的 ChatMessage 是抽象类型，不同子类获取文本的方式不同，这里统一适配。
     * @return 消息文本，无法识别的类型返回空字符串
     */
    private String getMessageText(ChatMessage message) {
        if (message instanceof SystemMessage sm) return sm.text();
        if (message instanceof UserMessage um) return um.singleText();
        if (message instanceof AiMessage am) return am.text();
        return "";
    }

    /**
     * 判断是否需要生成摘要，如果需要则异步调用 LLM 生成并存储。
     *
     * <p>触发条件：被淘汰的消息数 ≥ {@link #SUMMARY_TRIGGER_DROPPED_MESSAGES}（4 条）。</p>
     *
     * <p>摘要策略：</p>
     * <ul>
     *   <li>将已有摘要 + 淘汰消息的对话文本拼成 prompt，让 LLM 合并生成新摘要。</li>
     *   <li>使用 {@link CompletableFuture#orTimeout} 限制 LLM 调用不超过 1.2 秒，
     *       超时则保留旧摘要，避免阻塞用户请求。</li>
     *   <li>摘要超过 2000 字符时截断，防止异常情况。</li>
     * </ul>
     *
     * @param summaryKey      摘要在 Redis 中的完整 key
     * @param droppedMessages 被滑动窗口淘汰的旧消息列表
     */
    private void maybeSummarizeAndStore(String summaryKey, List<ChatMessage> droppedMessages) {
        // 淘汰消息不足 4 条时不触发摘要，避免频繁调用 LLM 造成延迟和成本浪费
        if (droppedMessages == null || droppedMessages.size() < SUMMARY_TRIGGER_DROPPED_MESSAGES) {
            return;
        }
        // 读取已有摘要（首次生成时为空）
        String previousSummary = getSummaryByKey(summaryKey);
        // 将淘汰消息转为 "User: xxx\nAI: xxx" 格式的对话文本
        String deltaText = toTranscript(droppedMessages);
        if (deltaText.isBlank()) {
            return;
        }

        String prompt = """
                你是对话摘要助手。请把新增历史对话合并到已有摘要中，输出简洁、可复用的事实摘要。
                要求：
                1. 使用中文；
                2. 最多 500 字；
                3. 保留关键术语、研究结论、用户偏好、约束和待办；
                4. 不要编造；
                5. 只输出摘要正文。

                已有摘要：
                %s

                新增对话：
                %s
                """.formatted(previousSummary == null || previousSummary.isBlank() ? "（无）" : previousSummary, deltaText);

        // 默认保留旧摘要，LLM 成功时才替换
        String mergedSummary = previousSummary;
        try {
            mergedSummary = CompletableFuture
                    // supplyAsync 在公共 ForkJoinPool 中执行，不阻塞当前请求线程
                    .supplyAsync(() -> cacheService.getOrCompute(
                            "conversation-summary-v1",
                            prompt,
                            () -> chatModel.generate(prompt)))
                    // 1.2 秒超时：摘要生成是"尽力而为"的，不能拖慢用户请求
                    .orTimeout(SUMMARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    // 超时或异常时回退到旧摘要，保证不会丢失已有的上下文
                    .exceptionally(ex -> previousSummary)
                    .join();
        } catch (Exception e) {
            log.warn("Conversation summary generation failed | error={}", e.getMessage());
        }
        if (mergedSummary == null || mergedSummary.isBlank()) {
            return;
        }
        // 截断过长摘要，防止极端情况下摘要本身变成性能问题
        if (mergedSummary.length() > MAX_SUMMARY_CHARS) {
            mergedSummary = mergedSummary.substring(0, MAX_SUMMARY_CHARS);
        }
        saveSummary(summaryKey, mergedSummary.trim());
    }

    /**
     * 将消息列表转为可读的对话文本格式（"User: xxx\nAI: xxx"）。
     * 用于拼入 LLM 的摘要 prompt，只保留 User 和 AI 消息，忽略 SystemMessage。
     *
     * @param messages 消息列表
     * @return 对话文本，无有效消息时返回空字符串
     */
    private String toTranscript(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage um) {
                sb.append("User: ").append(um.singleText()).append("\n");
            } else if (message instanceof AiMessage am) {
                sb.append("AI: ").append(am.text()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 保存摘要到 Redis，Redis 不可用时降级写入本地 Map。
     * @param summaryKey 摘要的完整 Redis key
     * @param summary    摘要文本
     */
    private void saveSummary(String summaryKey, String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        if (isRedisAvailable()) {
            try {
                redisTemplate.opsForValue().set(summaryKey, summary, SUMMARY_TTL);
                return;
            } catch (Exception e) {
                // Redis 写入失败 → 降级到本地，同时标记 Redis 不可用
                markRedisUnavailable();
            }
        }
        // 降级写入本地摘要缓存，超过上限时自动淘汰最早的条目
        synchronized (fallbackLock) {
            localSummaries.put(summaryKey, summary);
            trim(localSummaries);
        }
    }

    /**
     * 根据摘要 key 读取摘要文本，优先走 Redis，Redis 异常时回退到本地缓存。
     * @param summaryKey 摘要的完整 Redis key
     * @return 摘要文本，不存在时返回空字符串
     */
    private String getSummaryByKey(String summaryKey) {
        if (isRedisAvailable()) {
            try {
                String summary = redisTemplate.opsForValue().get(summaryKey);
                if (summary != null && !summary.isBlank()) {
                    return summary;
                }
                // Redis 返回空不代表摘要不存在——可能尚未生成，继续查本地缓存兜底
            } catch (Exception e) {
                markRedisUnavailable();
            }
        }
        // Redis 不可用或返回空时，从本地降级缓存读取
        synchronized (fallbackLock) {
            return localSummaries.getOrDefault(summaryKey, "");
        }
    }

    /**
     * 将消息 JSON 保存到本地降级缓存（加锁）。
     * 过大的 JSON（超过 200KB）会被丢弃，防止撑爆 JVM 堆内存。
     *
     * @param key 消息的完整 Redis key
     * @param json 消息 JSON 字符串
     */
    private void saveToLocal(String key, String json) {
        // 超大 JSON 直接丢弃——降级存储的目的是"兜底"，不应为此消耗大量堆内存
        if (json == null || json.isBlank() || json.length() > MAX_FALLBACK_JSON_CHARS) {
            return;
        }
        synchronized (fallbackLock) {
            localMessages.put(key, json);
            trim(localMessages);
        }
    }

    /**
     * 裁剪本地降级 Map，确保不超过 {@link #MAX_FALLBACK_ENTRIES}（1000 条）。
     * 利用 LinkedHashMap 的插入顺序，移除最早加入的 entry（LRU 语义）。
     */
    private void trim(Map<String, String> map) {
        // LinkedHashMap 默认按插入顺序迭代，iterator().next() 返回的就是最早插入的 key
        while (map.size() > MAX_FALLBACK_ENTRIES) {
            String eldest = map.keySet().iterator().next();
            map.remove(eldest);
        }
    }

    /**
     * 将本地降级缓存中的全部数据 flush 回 Redis。
     *
     * <p>在 Redis 从不可用恢复为可用时由 {@link #isRedisAvailable()} 调用。
     * 流程：先在锁内拷贝一份快照 → 逐条写入 Redis（带 TTL）→ 成功后清空本地缓存。
     * 如果写入过程中 Redis 再次故障，会重新标记为不可用，未写入的数据仍保留在本地。</p>
     */
    private void flushLocalFallbackToRedis() {
        Map<String, String> messages;
        Map<String, String> summaries;
        synchronized (fallbackLock) {
            // 在锁内做快照拷贝，避免持锁期间做 Redis IO 导致锁等待时间过长
            messages = new LinkedHashMap<>(localMessages);
            summaries = new LinkedHashMap<>(localSummaries);
        }
        try {
            for (Map.Entry<String, String> entry : messages.entrySet()) {
                redisTemplate.opsForValue().set(entry.getKey(), entry.getValue(), MESSAGE_TTL);
            }
            for (Map.Entry<String, String> entry : summaries.entrySet()) {
                redisTemplate.opsForValue().set(entry.getKey(), entry.getValue(), SUMMARY_TTL);
            }
            // 全部写入成功后才清空本地缓存，保证数据不丢失
            synchronized (fallbackLock) {
                localMessages.clear();
                localSummaries.clear();
            }
        } catch (Exception e) {
            // flush 过程中 Redis 再次故障 → 重新标记不可用，本地数据仍保留
            markRedisUnavailable();
        }
    }

    /**
     * 将 ChatMessage 列表序列化为 JSON 字符串。
     *
     * <p>格式为 [{"type":"USER","content":"..."},{"type":"AI","content":"..."}]，
     * 只保留类型和文本内容，丢弃其他元数据（如图片、工具调用等），
     * 因为本系统主要处理纯文本对话。</p>
     *
     * @param messages 消息列表
     * @return JSON 字符串
     * @throws JsonProcessingException 序列化失败时抛出
     */
    private String serializeMessages(List<ChatMessage> messages) throws JsonProcessingException {
        List<Map<String, String>> rows = new ArrayList<>();
        for (ChatMessage message : messages) {
            rows.add(Map.of("type", message.type().name(), "content", getMessageText(message)));
        }
        return objectMapper.writeValueAsString(rows);
    }

    /**
     * 将 JSON 字符串反序列化为 ChatMessage 列表。
     * 解析失败时返回空列表并记录日志，不会抛异常到上层。
     *
     * @param json JSON 字符串
     * @return 消息列表，解析失败时返回空列表
     */
    private List<ChatMessage> deserializeMessages(String json) {
        try {
            List<Map<String, String>> rows = objectMapper.readValue(json, new TypeReference<>() {});
            List<ChatMessage> messages = new ArrayList<>();
            for (Map<String, String> row : rows) {
                ChatMessage message = deserializeMessage(row.get("type"), row.get("content"));
                if (message != null) {
                    messages.add(message);
                }
            }
            return messages;
        } catch (Exception e) {
            log.warn("Failed to deserialize chat memory | error={}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 根据类型字符串和文本内容，反序列化为具体的 ChatMessage 子类实例。
     * 使用 Java 17 的 switch 表达式，简洁地处理三种消息类型。
     *
     * @param type    消息类型字符串（"SYSTEM"/"USER"/"AI"）
     * @param content 消息文本内容
     * @return 对应的 ChatMessage 实例，类型无法识别时返回 null
     */
    private ChatMessage deserializeMessage(String type, String content) {
        if (type == null || content == null) {
            return null;
        }
        return switch (type) {
            case "SYSTEM" -> new SystemMessage(content);
            case "USER" -> new UserMessage(content);
            case "AI" -> new AiMessage(content);
            default -> null;
        };
    }
}
