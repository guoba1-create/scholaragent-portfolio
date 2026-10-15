package com.team.rag.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.team.rag.monitoring.AgentBusinessMetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * LLM 响应两级缓存服务（L1 + L2 Cache-Aside 模式）
 *
 * <h3>职责</h3>
 * 对 LLM（大语言模型）的响应结果进行缓存，避免相同 prompt 重复调用 LLM，
 * 从而降低延迟、节省 token 费用、提高系统吞吐量。
 *
 * <h3>两级缓存架构</h3>
 * <pre>
 *   请求 → L1 缓存（Caffeine 本地内存）
 *               ↓ 未命中
 *          L2 缓存（Redis 远程内存）
 *               ↓ 未命中
 *          调用 LLM（Supplier）
 *               ↓ 写回
 *          L1 + L2 双缓存
 * </pre>
 * <ul>
 *   <li><b>L1（Caffeine）</b>：进程内内存缓存，默认 500 条、10 分钟 TTL，微秒级命中</li>
 *   <li><b>L2（Redis）</b>：分布式缓存，默认 1 小时 TTL，毫秒级命中</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>全局开关：通过 {@code llm.cache.enabled} 配置项一键关闭缓存，方便调试</li>
 *   <li>Redis 降级：Redis 操作全部 try-catch 包裹，Redis 宕机时静默降级，不影响主流程</li>
 *   <li>缓存键：SHA-256(namespace + ":" + prompt) 截取前 32 位十六进制字符，保证唯一性且长度可控</li>
 * </ul>
 *
 * <h3>在系统中的位置</h3>
 * 位于 RAG（检索增强生成）模块的缓存层，被上层的 LLM 调用服务注入使用。
 * 属于"横切关注点"，对业务代码透明，只需通过 {@link #getOrCompute} 一行代码即可接入。
 *
 * @see com.github.benmanes.caffeine.cache.Cache Caffeine 本地缓存
 * @see org.springframework.data.redis.core.StringRedisTemplate Redis 操作模板
 */
@Service
public class LlmResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseCacheService.class);
    /** Redis 缓存键前缀，用于命名空间隔离，避免与其他业务 key 冲突 */
    private static final String REDIS_KEY_PREFIX = "llm:cache:";

    /** L1 本地缓存（Caffeine），key 为 SHA-256 哈希值，value 为 LLM 原始响应文本 */
    private final Cache<String, String> memoryCache;
    /** L2 分布式缓存的客户端，通过 Spring Boot 自动注入；可选依赖，允许为 null */
    private final StringRedisTemplate redisTemplate;
    /** 全局缓存开关，为 false 时所有缓存操作直接跳过，等效于关闭缓存 */
    private final boolean enabled;
    /** Redis 缓存过期时间，由配置项 {@code llm.cache.redis-ttl-hours} 控制 */
    private final Duration redisTtl;
    private final AgentBusinessMetrics metrics;

    /**
     * 构造器：通过 Spring 依赖注入初始化两级缓存的所有参数。
     *
     * <p>Caffeine 缓存在构造时一次性创建，内部采用 Window-TinyLFU 淘汰算法，
     * 在命中率和内存占用之间取得最佳平衡。{@code recordStats()} 开启统计功能，
     * 供 {@link #stats()} 方法暴露监控指标。</p>
     *
     * @param redisTemplate    Redis 操作模板，可为 null（无 Redis 环境时自动降级）
     * @param enabled          缓存全局开关，默认 true；设置为 false 可绕过缓存直接调用 LLM
     * @param memoryMaxSize    L1 缓存最大条目数，超出后按 TinyLFU 策略淘汰最不常访问的条目
     * @param memoryTtlMinutes L1 缓存写入后过期时间（分钟），过期条目在下次访问时惰性清除
     * @param redisTtlHours    L2 缓存写入后过期时间（小时）
     */
    public LlmResponseCacheService(
            StringRedisTemplate redisTemplate,
            AgentBusinessMetrics metrics,
            @Value("${llm.cache.enabled:true}") boolean enabled,
            @Value("${llm.cache.memory-max-size:500}") long memoryMaxSize,
            @Value("${llm.cache.memory-ttl-minutes:10}") long memoryTtlMinutes,
            @Value("${llm.cache.redis-ttl-hours:1}") long redisTtlHours) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.enabled = enabled;
        this.redisTtl = Duration.ofHours(redisTtlHours);
        // Caffeine.newBuilder() 是线程安全的构建器，build() 后得到的 Cache 实例也是线程安全的
        this.memoryCache = Caffeine.newBuilder()
                .maximumSize(memoryMaxSize)
                .expireAfterWrite(Duration.ofMinutes(memoryTtlMinutes))
                .recordStats()  // 开启命中率/淘汰率统计，供监控使用
                .build();
        log.info("LlmResponseCacheService initialized | enabled={}, memoryMaxSize={}, memoryTtl={}min, redisTtl={}h",
                enabled, memoryMaxSize, memoryTtlMinutes, redisTtlHours);
    }

    /**
     * 核心方法：Cache-Aside 模式的入口，一行代码即可接入缓存。
     *
     * <p>执行流程：检查开关 → 查 L1 → 查 L2 → 调用 LLM → 写回双缓存。</p>
     * <p>示例用法：</p>
     * <pre>{@code
     * String answer = cacheService.getOrCompute("qa", userQuestion, () -> llmClient.chat(userQuestion));
     * }</pre>
     *
     * @param namespace 缓存命名空间，用于隔离不同业务场景（如 "qa"、"summary"、"translate"）
     * @param prompt    用户输入的 prompt 文本，与 namespace 组合后进行 SHA-256 哈希生成缓存键
     * @param llmCall   实际调用 LLM 的懒加载函数，仅在缓存全部未命中时才执行
     * @return LLM 的响应文本，优先返回缓存结果
     */
    public String getOrCompute(String namespace, String prompt, Supplier<String> llmCall) {
        if (!enabled) {
            metrics.cacheLookup(namespace, "bypass", false);
            // 缓存关闭时，直接穿透调用 LLM，等效于无缓存模式
            return llmCall.get();
        }

        String cached = get(namespace, prompt);
        if (cached != null) {
            return cached;
        }

        // 两级缓存都未命中，调用 LLM 获取真实结果
        String result = llmCall.get();
        // 写回双缓存：下次相同请求将从 L1 命中，无需穿透到 Redis 或 LLM
        put(namespace, prompt, result);
        return result;
    }

    /**
     * 查询缓存：先查 L1（Caffeine），再查 L2（Redis）。
     *
     * <p>当 L2 命中而 L1 未命中时，会自动将结果"回填"到 L1，
     * 这样下次相同请求将直接从 L1 微秒级返回，无需再走 Redis 网络。</p>
     *
     * @param namespace 缓存命名空间
     * @param prompt    用户输入的 prompt 文本
     * @return 缓存的 LLM 响应文本；如果两级缓存都未命中则返回 null
     */
    public String get(String namespace, String prompt) {
        if (!enabled) {
            return null;
        }

        // 将 namespace + prompt 组合后哈希为固定长度的缓存键，避免 key 过长
        String hashKey = hashPrompt(namespace, prompt);

        // ---- L1 查询（进程内内存，微秒级） ----
        String cached = memoryCache.getIfPresent(hashKey);
        if (cached != null) {
            log.debug("L1 cache hit | namespace={}", namespace);
            metrics.cacheLookup(namespace, "l1", true);
            return cached;
        }

        // Redis 未注入时（如本地开发无 Redis），直接返回 null，避免 NPE
        if (redisTemplate == null) {
            metrics.cacheLookup(namespace, "l2", false);
            return null;
        }

        // ---- L2 查询（Redis 网络调用，毫秒级） ----
        try {
            cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + hashKey);
            if (cached != null) {
                log.debug("L2 cache hit | namespace={}", namespace);
                metrics.cacheLookup(namespace, "l2", true);
                // L2 命中后回填 L1：下次相同请求将直接从 L1 返回，减少 Redis 访问
                memoryCache.put(hashKey, cached);
                return cached;
            }
        } catch (Exception e) {
            // Redis 异常静默处理：只打印 warn 日志，不影响主流程（降级为无 L2 缓存模式）
            log.warn("Redis cache read failed | error={}", e.getMessage());
            metrics.cacheError("read");
        }
        metrics.cacheLookup(namespace, "all", false);
        return null;
    }

    /**
     * 写入缓存：同时写入 L1（Caffeine）和 L2（Redis），保持两级缓存数据一致。
     *
     * <p>写入 Redis 时会设置 TTL（默认 1 小时），过期后自动清除，防止缓存无限膨胀。</p>
     *
     * @param namespace 缓存命名空间
     * @param prompt    用户输入的 prompt 文本
     * @param result    LLM 返回的响应文本；如果为 null 则不缓存（避免缓存空值）
     */
    public void put(String namespace, String prompt, String result) {
        // result 为 null 时不缓存，防止缓存"空洞"导致后续请求误判为命中
        if (!enabled || result == null) {
            return;
        }

        String hashKey = hashPrompt(namespace, prompt);

        // 写入 L1（内存缓存），受 Caffeine 的 maximumSize 和 TTL 约束自动淘汰
        memoryCache.put(hashKey, result);

        if (redisTemplate == null) {
            return;
        }

        // 写入 L2（Redis），带 TTL 自动过期；Redis 异常时静默降级，不影响主流程
        try {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + hashKey, result, redisTtl);
        } catch (Exception e) {
            log.warn("Redis cache write failed | error={}", e.getMessage());
            metrics.cacheError("write");
        }
    }

    /**
     * 主动驱逐缓存：同时清除 L1 和 L2 中对应条目。
     *
     * <p>典型场景：当知识库更新、prompt 模板变更等导致旧缓存失效时，
     * 由业务层主动调用此方法清除对应缓存，确保下次请求能获取最新 LLM 响应。</p>
     *
     * @param namespace 缓存命名空间
     * @param prompt    需要清除缓存的 prompt 文本
     */
    public void evict(String namespace, String prompt) {
        String hashKey = hashPrompt(namespace, prompt);

        // 先清 L1（内存），Caffeine 的 invalidate 是同步操作，立即生效
        memoryCache.invalidate(hashKey);

        if (redisTemplate == null) {
            return;
        }

        // 再清 L2（Redis），Redis 异常时静默降级，不影响主流程
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX + hashKey);
        } catch (Exception e) {
            log.warn("Redis cache evict failed | error={}", e.getMessage());
            metrics.cacheError("evict");
        }
    }

    /**
     * 获取 L1（Caffeine）缓存的运行时统计信息。
     *
     * <p>返回的 {@link com.github.benmanes.caffeine.cache.stats.CacheStats} 包含：</p>
     * <ul>
     *   <li>命中率（hitRate）：L1 命中次数 / 总查询次数</li>
     *   <li>淘汰率（evictionCount）：因容量满或 TTL 过期被淘汰的条目数</li>
     *   <li>加载耗时（totalLoadTime）：所有 cache-miss 加载操作的累计耗时</li>
     * </ul>
     * <p>可用于 Prometheus/Grafana 等监控系统的指标采集。</p>
     *
     * @return Caffeine 缓存的统计数据快照
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return memoryCache.stats();
    }

    /**
     * 将 namespace + prompt 组合后计算 SHA-256 哈希，截取前 32 位十六进制字符作为缓存键。
     *
     * <p>为什么用哈希而不是原始文本？</p>
     * <ul>
     *   <li>Prompt 可能很长（数千字符），直接作为 Redis key 浪费存储和网络带宽</li>
     *   <li>SHA-256 输出固定 256 bit = 64 位十六进制，截取前 32 位已足够避免碰撞（碰撞概率 ~2^-128）</li>
     *   <li>哈希值只包含 [0-9a-f]，不会出现 Redis key 中的特殊字符问题</li>
     * </ul>
     *
     * @param namespace 缓存命名空间
     * @param prompt    用户输入的 prompt 文本
     * @return 32 位十六进制字符串，用作 L1 和 L2 的缓存键
     */
    private String hashPrompt(String namespace, String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 用 namespace + ":" + prompt 拼接后哈希，冒号分隔确保不同 namespace 下相同 prompt 产生不同 key
            byte[] hash = digest.digest((namespace + ":" + prompt).getBytes(StandardCharsets.UTF_8));
            // 截取前 32 位十六进制字符（128 bit），在碰撞概率和 key 长度之间取得平衡
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标准算法，理论上不会抛出此异常；这里是兜底降级方案
            return namespace + ":" + Integer.toHexString(prompt.hashCode());
        }
    }
}
