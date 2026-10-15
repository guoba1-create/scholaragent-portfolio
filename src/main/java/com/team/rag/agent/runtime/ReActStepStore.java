package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.model.ReActStep;
import com.team.rag.infrastructure.RedisRetryGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ReAct 步骤存储 —— 管理 ReAct 循环中每一步（Thought/Action/Observation）的持久化。
 *
 * <p>职责：
 * <ul>
 *   <li>维护每个工作流对应的 ReActStep 列表</li>
 *   <li>提供本地内存 + Redis 双层存储，保证高性能读取和跨实例持久化</li>
 *   <li>支持步骤的追加、批量设置、查询和清除操作</li>
 * </ul>
 *
 * <p>设计思想：
 * <ul>
 *   <li><b>本地优先</b>：所有读写优先操作本地 ConcurrentHashMap，Redis 作为持久化备份</li>
 *   <li><b>优雅降级</b>：Redis 不可用时通过 {@code redisAvailable} 标志自动切换为纯内存模式，
 *       不抛异常，不影响业务流程</li>
 *   <li><b>线程安全</b>：本地存储使用 {@code Collections.synchronizedList} 保证并发安全</li>
 *   <li><b>TTL 过期</b>：Redis 数据设置 7 天 TTL，自动清理过期工作流数据</li>
 * </ul>
 *
 * <p>在系统中的位置：ReAct Runtime 每执行一步（Thought -> Action -> Observation）后，
 * 将 ReActStep 追加到本存储；前端通过 API 查询步骤列表展示推理过程。
 */
@Component
public class ReActStepStore {

    private static final Logger log = LoggerFactory.getLogger(ReActStepStore.class);

    /** Redis 数据的生存时间：7 天 */
    private static final Duration TTL = Duration.ofDays(7);

    /** JSON 序列化器，用于 ReActStep <-> JSON 字符串的转换 */
    private final ObjectMapper objectMapper;

    /** Redis 操作模板 */
    private final StringRedisTemplate redisTemplate;

    /** 本地内存存储：workflowId -> ReActStep 列表（线程安全） */
    private final Map<String, List<ReActStep>> stepsByWorkflow = new ConcurrentHashMap<>();

    private static final Duration LOCAL_RETENTION = Duration.ofHours(12);
    private static final int MAX_LOCAL_WORKFLOWS = 1500;
    private final RedisRetryGate redisGate = new RedisRetryGate(Duration.ofSeconds(30));
    private final Map<String, Instant> touchedAt = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong();

    public ReActStepStore(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 清除指定工作流的所有步骤。
     * <p>同时清理本地内存和 Redis。
     *
     * @param workflowId 工作流标识
     */
    public void clear(String workflowId) {
        stepsByWorkflow.remove(workflowId);
        touchedAt.remove(workflowId);
        if (redisGate.canAttempt()) {
            try {
                redisTemplate.delete(key(workflowId));
                redisGate.success();
            } catch (Exception e) {
                redisGate.failure();
            }
        }
    }

    /**
     * 追加一个 ReAct 步骤。
     * <p>先写入本地内存，再异步持久化到 Redis。
     *
     * @param workflowId 工作流标识
     * @param step       待追加的步骤
     */
    public void addStep(String workflowId, ReActStep step) {
        // computeIfAbsent 保证同一 workflowId 只创建一次 synchronizedList
        stepsByWorkflow.computeIfAbsent(workflowId, k -> Collections.synchronizedList(new ArrayList<>())).add(step);
        touch(workflowId);
        cleanupIfDue();
        if (redisGate.canAttempt()) {
            try {
                // 使用 List 的 rightPush 保证步骤顺序
                redisTemplate.opsForList().rightPush(key(workflowId), objectMapper.writeValueAsString(step));
                redisTemplate.expire(key(workflowId), TTL);
                redisGate.success();
            } catch (Exception e) {
                redisGate.failure();
                log.warn("ReAct step Redis persistence cooling down for 30s | error={}", e.getMessage());
            }
        }
    }

    /**
     * 批量设置工作流的步骤列表（替换已有数据）。
     * <p>先清除旧数据，再写入新数据。
     *
     * @param workflowId 工作流标识
     * @param steps      新的步骤列表
     */
    public void setSteps(String workflowId, List<ReActStep> steps) {
        clear(workflowId);
        stepsByWorkflow.put(workflowId, Collections.synchronizedList(new ArrayList<>(steps)));
        touch(workflowId);
        if (redisGate.canAttempt()) {
            try {
                for (ReActStep step : steps) {
                    redisTemplate.opsForList().rightPush(key(workflowId), objectMapper.writeValueAsString(step));
                }
                redisTemplate.expire(key(workflowId), TTL);
                redisGate.success();
            } catch (Exception e) {
                redisGate.failure();
            }
        }
    }

    /**
     * 获取指定工作流的所有步骤。
     * <p>优先从本地内存读取；本地无数据时尝试从 Redis 加载并回填本地缓存。
     *
     * @param workflowId 工作流标识
     * @return 步骤列表的副本，保证不会被外部修改
     */
    public List<ReActStep> getSteps(String workflowId) {
        List<ReActStep> local = stepsByWorkflow.get(workflowId);
        if (local != null && !local.isEmpty()) {
            // 返回副本，避免外部修改影响内部状态
            synchronized (local) {
                touch(workflowId);
                return new ArrayList<>(local);
            }
        }
        // 本地无数据，尝试从 Redis 持久化层加载
        List<ReActStep> persisted = loadPersisted(workflowId);
        if (!persisted.isEmpty()) {
            // 回填本地缓存，后续读取直接命中内存
            stepsByWorkflow.put(workflowId, Collections.synchronizedList(new ArrayList<>(persisted)));
        }
        return persisted;
    }

    /**
     * 从 Redis 加载持久化的步骤数据。
     *
     * @param workflowId 工作流标识
     * @return 从 Redis 反序列化的步骤列表，Redis 不可用时返回空列表
     */
    private List<ReActStep> loadPersisted(String workflowId) {
        if (!redisGate.canAttempt()) {
            return List.of();
        }
        try {
            // 读取 List 中所有元素（range 0 到 -1 表示全部）
            List<String> rows = redisTemplate.opsForList().range(key(workflowId), 0, -1);
            if (rows == null || rows.isEmpty()) {
                redisGate.success();
                return List.of();
            }
            List<ReActStep> steps = new ArrayList<>();
            for (String row : rows) {
                steps.add(objectMapper.readValue(row, ReActStep.class));
            }
            redisGate.success();
            return steps;
        } catch (Exception e) {
            redisGate.failure();
            log.warn("ReAct step Redis replay cooling down for 30s | error={}", e.getMessage());
            return List.of();
        }
    }

    private void touch(String workflowId) {
        touchedAt.put(workflowId, Instant.now());
    }

    private void cleanupIfDue() {
        if (accessCounter.incrementAndGet() % 64 != 0) return;
        Instant cutoff = Instant.now().minus(LOCAL_RETENTION);
        touchedAt.entrySet().removeIf(entry -> {
            if (!entry.getValue().isBefore(cutoff)) return false;
            stepsByWorkflow.remove(entry.getKey());
            return true;
        });
        if (stepsByWorkflow.size() <= MAX_LOCAL_WORKFLOWS) return;
        touchedAt.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(stepsByWorkflow.size() - MAX_LOCAL_WORKFLOWS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(id -> { stepsByWorkflow.remove(id); touchedAt.remove(id); });
    }

    /**
     * 生成 Redis 键名。
     * <p>格式：workflow:react-steps:{workflowId}
     */
    private String key(String workflowId) {
        return "workflow:react-steps:" + workflowId;
    }
}
