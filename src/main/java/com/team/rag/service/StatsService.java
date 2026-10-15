package com.team.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 统计服务 —— 管理系统运行统计数据
 *
 * <p>
 * 使用 Redis 存储统计数据，支持：
 * <ul>
 * <li>对话次数统计（按日期）</li>
 * <li>文献上传统计</li>
 * <li>记忆保存统计</li>
 * <li>响应时间统计</li>
 * </ul>
 *
 * <h3>Redis Key 设计：</h3>
 * <ul>
 * <li>stats:conversations:total - 总对话数</li>
 * <li>stats:documents:total - 总文献数</li>
 * <li>stats:memories:total - 总记忆数</li>
 * <li>stats:response_times - 响应时间列表（用于计算平均值）</li>
 * <li>stats:conversations:daily:{date} - 每日对话数</li>
 * <li>stats:usage:{type} - 各功能使用次数</li>
 * </ul>
 */
/**
 * 统计服务 —— 管理系统运行统计数据。
 *
 * <p>职责：收集和查询系统运行指标，包括：</p>
 * <ul>
 *   <li>对话次数统计（按日期）</li>
 *   <li>文献上传统计</li>
 *   <li>记忆保存统计</li>
 *   <li>响应时间统计</li>
 *   <li>各功能使用次数统计</li>
 * </ul>
 *
 * <h3>设计思想：</h3>
 * <p>采用"Redis + 本地内存"的双层存储架构，与 DocumentManifestStore 类似：</p>
 * <ul>
 *   <li>Redis - 持久化存储，支持跨实例共享和重启恢复</li>
 *   <li>本地内存 - 内存缓存，Redis 不可用时自动降级</li>
 * </ul>
 *
 * <h3>Redis Key 设计：</h3>
 * <ul>
 *   <li>stats:conversations:total - 总对话数（String，数字）</li>
 *   <li>stats:documents:total - 总文献数（String，数字）</li>
 *   <li>stats:memories:total - 总记忆数（String，数字）</li>
 *   <li>stats:response_times - 响应时间列表（String，逗号分隔的数字，滑动窗口 100 条）</li>
 *   <li>stats:conversations:daily:{date} - 每日对话数（String，数字）</li>
 *   <li>stats:usage:{type} - 各功能使用次数（String，数字）</li>
 * </ul>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * AsyncDocumentProcessService（异步文档处理）
 *     └── StatsService（本类：记录文献上传统计）
 *
 * LiteratureDialogueService（文献对话服务）
 *     └── StatsService（本类：记录对话次数和响应时间）
 * </pre>
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    /** Redis Key：总对话数 */
    private static final String KEY_CONVERSATIONS_TOTAL = "stats:conversations:total";

    /** Redis Key：总文献数 */
    private static final String KEY_DOCUMENTS_TOTAL = "stats:documents:total";

    /** Redis Key：总记忆数 */
    private static final String KEY_MEMORIES_TOTAL = "stats:memories:total";

    /** Redis Key：响应时间列表（滑动窗口，最多 100 条） */
    private static final String KEY_RESPONSE_TIMES = "stats:response_times";

    /** Redis Key 前缀：每日对话数（如 stats:conversations:daily:2024-01-15） */
    private static final String KEY_CONVERSATIONS_DAILY = "stats:conversations:daily:";

    /** Redis Key 前缀：各功能使用次数（如 stats:usage:chat） */
    private static final String KEY_USAGE_PREFIX = "stats:usage:";

    /** Redis 操作模板 */
    private final StringRedisTemplate redisTemplate;

    /** Redis 可用性标志：volatile 确保多线程可见性 */
    private volatile boolean redisAvailable = false;

    /** 本地计数器：总对话数（Redis 不可用时使用） */
    private long localConversationCount = 0;

    /** 本地计数器：总文献数（Redis 不可用时使用） */
    private long localDocumentCount = 0;

    /** 本地计数器：总记忆数（Redis 不可用时使用） */
    private long localMemoryCount = 0;

    /** 本地响应时间列表（滑动窗口，最多 100 条） */
    private final List<Long> localResponseTimes = new ArrayList<>();

    /** 本地功能使用次数统计 */
    private final Map<String, Integer> localUsageCount = new HashMap<>();

    @Autowired
    public StatsService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        initStats();
    }

    /**
     * 初始化统计服务。
     *
     * <p>初始化流程：</p>
     * <ol>
     *   <li>测试 Redis 连接</li>
     *   <li>如果 Redis 可用，初始化计数器 Key（不存在时设为 0）</li>
     *   <li>如果 Redis 不可用，降级为本地模式</li>
     * </ol>
     */
    private void initStats() {
        try {
            // 测试 Redis 连接
            redisAvailable = testRedisConnection();
            if (redisAvailable) {
                // 初始化计数器 Key（如果不存在则设为 0）
                // 使用 hasKey() 检查，避免覆盖已有的数据
                if (Boolean.FALSE.equals(redisTemplate.hasKey(KEY_CONVERSATIONS_TOTAL))) {
                    redisTemplate.opsForValue().set(KEY_CONVERSATIONS_TOTAL, "0");
                }
                if (Boolean.FALSE.equals(redisTemplate.hasKey(KEY_DOCUMENTS_TOTAL))) {
                    redisTemplate.opsForValue().set(KEY_DOCUMENTS_TOTAL, "0");
                }
                if (Boolean.FALSE.equals(redisTemplate.hasKey(KEY_MEMORIES_TOTAL))) {
                    redisTemplate.opsForValue().set(KEY_MEMORIES_TOTAL, "0");
                }
                log.info("统计服务初始化完成 (Redis模式)");
            } else {
                log.warn("统计服务初始化完成 (本地模式 - Redis不可用)");
            }
        } catch (Exception e) {
            redisAvailable = false;
            log.warn("统计服务初始化失败，使用本地模式: {}", e.getMessage());
        }
    }

    /**
     * 测试 Redis 连接是否可用。
     *
     * @return true 如果连接成功，false 如果连接失败
     */
    private boolean testRedisConnection() {
        try {
            // 使用 PING 命令测试连接
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            log.warn("Redis连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取总对话数。
     *
     * @return 总对话数
     */
    public long getTotalConversations() {
        if (redisAvailable) {
            try {
                String value = redisTemplate.opsForValue().get(KEY_CONVERSATIONS_TOTAL);
                return value != null ? Long.parseLong(value) : 0;
            } catch (Exception e) {
                log.warn("读取Redis失败: {}", e.getMessage());
            }
        }
        // Redis 不可用时返回本地计数
        return localConversationCount;
    }

    /**
     * 获取总文献数。
     *
     * @return 总文献数
     */
    public long getTotalDocuments() {
        if (redisAvailable) {
            try {
                String value = redisTemplate.opsForValue().get(KEY_DOCUMENTS_TOTAL);
                return value != null ? Long.parseLong(value) : 0;
            } catch (Exception e) {
                log.warn("读取Redis失败: {}", e.getMessage());
            }
        }
        return localDocumentCount;
    }

    /**
     * 获取总记忆数。
     *
     * @return 总记忆数
     */
    public long getTotalMemories() {
        if (redisAvailable) {
            try {
                String value = redisTemplate.opsForValue().get(KEY_MEMORIES_TOTAL);
                return value != null ? Long.parseLong(value) : 0;
            } catch (Exception e) {
                log.warn("读取Redis失败: {}", e.getMessage());
            }
        }
        return localMemoryCount;
    }

    /**
     * 获取平均响应时间。
     *
     * <p>基于滑动窗口（最近 100 条记录）计算平均响应时间。
     * 返回格式为 "xxxms"（如 "250ms"）。</p>
     *
     * @return 平均响应时间字符串
     */
    public String getAvgResponseTime() {
        List<Long> times;

        if (redisAvailable) {
            try {
                // 从 Redis 读取逗号分隔的响应时间列表
                String value = redisTemplate.opsForValue().get(KEY_RESPONSE_TIMES);
                if (value != null && !value.isEmpty()) {
                    times = new ArrayList<>();
                    for (String t : value.split(",")) {
                        if (!t.isEmpty()) {
                            times.add(Long.parseLong(t.trim()));
                        }
                    }
                } else {
                    return "0ms";
                }
            } catch (Exception e) {
                log.warn("读取Redis失败: {}", e.getMessage());
                return "0ms";
            }
        } else {
            // Redis 不可用时使用本地列表
            times = new ArrayList<>(localResponseTimes);
        }

        if (times.isEmpty()) {
            return "0ms";
        }

        // 计算平均值
        long sum = 0;
        for (Long time : times) {
            sum += time;
        }

        long avg = sum / times.size();
        return avg + "ms";
    }

    /**
     * 获取活动趋势（按日期统计对话数）。
     *
     * <p>返回最近 N 天的每日对话数，用于绘制趋势图。</p>
     *
     * @param days 天数
     * @return 趋势数据列表，每个元素包含 "date"（MM-dd 格式）和 "count"
     */
    public List<Map<String, Object>> getActivityTrend(int days) {
        List<Map<String, Object>> trend = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");

        // 从 days-1 天前到今天，逐日查询
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            String dateStr = date.format(DateTimeFormatter.ISO_DATE);
            long count = 0;

            if (redisAvailable) {
                try {
                    // 查询当日对话数
                    String key = KEY_CONVERSATIONS_DAILY + dateStr;
                    String value = redisTemplate.opsForValue().get(key);
                    count = value != null ? Long.parseLong(value) : 0;
                } catch (Exception e) {
                    log.warn("读取Redis失败: {}", e.getMessage());
                }
            }

            trend.add(Map.of(
                    "date", date.format(formatter),
                    "count", count
            ));
        }

        return trend;
    }

    /**
     * 获取各功能使用次数分布。
     *
     * <p>返回各功能模块的使用次数，用于生成饼图或柱状图。</p>
     *
     * @return 使用分布 Map，key 为功能名称，value 为使用次数
     */
    public Map<String, Integer> getUsageDistribution() {
        Map<String, Integer> distribution = new LinkedHashMap<>();

        if (redisAvailable) {
            try {
                // 从 Redis 读取各功能的使用次数
                String chatUsage = redisTemplate.opsForValue().get(KEY_USAGE_PREFIX + "chat");
                String docUsage = redisTemplate.opsForValue().get(KEY_USAGE_PREFIX + "document");
                String memoryUsage = redisTemplate.opsForValue().get(KEY_USAGE_PREFIX + "memory");
                String profileUsage = redisTemplate.opsForValue().get(KEY_USAGE_PREFIX + "profile");

                distribution.put("学术对话", chatUsage != null ? Integer.parseInt(chatUsage) : 0);
                distribution.put("文献上传", docUsage != null ? Integer.parseInt(docUsage) : 0);
                distribution.put("记忆管理", memoryUsage != null ? Integer.parseInt(memoryUsage) : 0);
                distribution.put("画像查询", profileUsage != null ? Integer.parseInt(profileUsage) : 0);
            } catch (Exception e) {
                log.warn("读取Redis失败: {}", e.getMessage());
                return getDefaultUsageDistribution();
            }
        } else {
            // Redis 不可用时使用本地计数
            distribution.put("学术对话", localUsageCount.getOrDefault("chat", 0));
            distribution.put("文献上传", localUsageCount.getOrDefault("document", 0));
            distribution.put("记忆管理", localUsageCount.getOrDefault("memory", 0));
            distribution.put("画像查询", localUsageCount.getOrDefault("profile", 0));
        }

        return distribution;
    }

    /**
     * 获取默认的使用分布（全为 0）。
     */
    private Map<String, Integer> getDefaultUsageDistribution() {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("学术对话", 0);
        distribution.put("文献上传", 0);
        distribution.put("记忆管理", 0);
        distribution.put("画像查询", 0);
        return distribution;
    }

    /**
     * 记录一次对话。
     *
     * <p>一次对话会更新以下统计：</p>
     * <ul>
     *   <li>总对话数 +1</li>
     *   <li>今日对话数 +1</li>
     *   <li>学术对话使用次数 +1</li>
     *   <li>响应时间列表追加（滑动窗口，最多 100 条）</li>
     * </ul>
     *
     * @param userId 用户标识（用于未来扩展用户级统计）
     * @param researchGroupId 研究组标识（用于未来扩展组级统计）
     * @param responseTime 响应时间（毫秒）
     */
    public void recordConversation(String userId, String researchGroupId, long responseTime) {
        if (redisAvailable) {
            try {
                // 原子递增总对话数
                redisTemplate.opsForValue().increment(KEY_CONVERSATIONS_TOTAL);

                // 原子递增今日对话数
                String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
                redisTemplate.opsForValue().increment(KEY_CONVERSATIONS_DAILY + today);

                // 原子递增学术对话使用次数
                redisTemplate.opsForValue().increment(KEY_USAGE_PREFIX + "chat");

                // 更新响应时间列表（滑动窗口，最多 100 条）
                String currentTimes = redisTemplate.opsForValue().get(KEY_RESPONSE_TIMES);
                String newTimes;
                if (currentTimes == null || currentTimes.isEmpty()) {
                    newTimes = String.valueOf(responseTime);
                } else {
                    String[] times = currentTimes.split(",");
                    if (times.length >= 100) {
                        // 超过 100 条时，移除最早的记录（滑动窗口）
                        newTimes = currentTimes.substring(currentTimes.indexOf(",") + 1) + "," + responseTime;
                    } else {
                        newTimes = currentTimes + "," + responseTime;
                    }
                }
                redisTemplate.opsForValue().set(KEY_RESPONSE_TIMES, newTimes);

                log.debug("对话统计已更新 | 总数={}, 今日={}, 响应时间={}ms",
                        getTotalConversations(),
                        redisTemplate.opsForValue().get(KEY_CONVERSATIONS_DAILY + today),
                        responseTime);
                return;
            } catch (Exception e) {
                // Redis 写入失败，降级为本地模式
                log.warn("写入Redis失败，切换到本地模式: {}", e.getMessage());
                redisAvailable = false;
            }
        }

        // 本地模式：更新本地计数器
        localConversationCount++;
        localUsageCount.merge("chat", 1, Integer::sum);
        synchronized (localResponseTimes) {
            localResponseTimes.add(responseTime);
            // 滑动窗口：超过 100 条时移除最早的记录
            if (localResponseTimes.size() > 100) {
                localResponseTimes.remove(0);
            }
        }
        log.debug("对话统计已更新(本地) | 总数={}, 响应时间={}ms", localConversationCount, responseTime);
    }

    /**
     * 记录一次文献上传。
     *
     * @param researchGroupId 研究组标识
     * @param segmentCount 分块数量（用于未来扩展统计分块总数）
     */
    public void recordDocumentUpload(String researchGroupId, int segmentCount) {
        if (redisAvailable) {
            try {
                redisTemplate.opsForValue().increment(KEY_DOCUMENTS_TOTAL);
                redisTemplate.opsForValue().increment(KEY_USAGE_PREFIX + "document");
                log.debug("文献统计已更新 | 总数={}", getTotalDocuments());
                return;
            } catch (Exception e) {
                log.warn("写入Redis失败: {}", e.getMessage());
                redisAvailable = false;
            }
        }

        localDocumentCount++;
        localUsageCount.merge("document", 1, Integer::sum);
        log.debug("文献统计已更新(本地) | 总数={}", localDocumentCount);
    }

    /**
     * 记录一次记忆保存。
     *
     * @param userId 用户标识
     * @param type 记忆类型（用于未来扩展分类统计）
     */
    public void recordMemorySave(String userId, String type) {
        if (redisAvailable) {
            try {
                redisTemplate.opsForValue().increment(KEY_MEMORIES_TOTAL);
                redisTemplate.opsForValue().increment(KEY_USAGE_PREFIX + "memory");
                log.debug("记忆统计已更新 | 总数={}", getTotalMemories());
                return;
            } catch (Exception e) {
                log.warn("写入Redis失败: {}", e.getMessage());
                redisAvailable = false;
            }
        }

        localMemoryCount++;
        localUsageCount.merge("memory", 1, Integer::sum);
        log.debug("记忆统计已更新(本地) | 总数={}", localMemoryCount);
    }

    /**
     * 记录一次画像查询。
     *
     * @param userId 用户标识
     */
    public void recordProfileQuery(String userId) {
        if (redisAvailable) {
            try {
                redisTemplate.opsForValue().increment(KEY_USAGE_PREFIX + "profile");
                log.debug("画像查询统计已更新");
                return;
            } catch (Exception e) {
                log.warn("写入Redis失败: {}", e.getMessage());
                redisAvailable = false;
            }
        }

        localUsageCount.merge("profile", 1, Integer::sum);
        log.debug("画像查询统计已更新(本地)");
    }

    /**
     * 检查 Redis 是否可用。
     *
     * @return true 如果 Redis 可用，false 如果已降级为本地模式
     */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }
}
