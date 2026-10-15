package com.team.rag.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.model.AgentMessage;
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
 * Agent 间消息总线 —— 内存存储 + Redis 持久化。
 *
 * <p>在多 Agent 协作场景下（如父 Agent Handoff 子 Agent），需要一个统一的消息通道来传递任务和结果。
 * 本类提供按 workflowId 和 messageId 的双索引存储，支持消息的发送、检索、状态更新和清理。
 *
 * <p>存储策略：
 * <ul>
 *   <li>内存层：{@code ConcurrentHashMap} 提供 O(1) 查找，作为热数据缓存</li>
 *   <li>持久层：Redis（key = {@code workflow:agent-messages:{workflowId}}，TTL = 7天），
 *       应用重启后可恢复消息历史；Redis 不可用时自动降级，不影响主流程</li>
 * </ul>
 *
 * <p>典型调用链：Python Handoff → gRPC → WorkflowRunService → AgentMessageBus.send()
 *           → 父 Agent 查询 → AgentMessageBus.getHandoffMessages()
 *
 * @see AgentMessage — 消息载体
 */
@Component
public class AgentMessageBus {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageBus.class);
    private static final Duration TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "workflow:agent-messages:";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private final Map<String, List<AgentMessage>> workflowMessages = new ConcurrentHashMap<>();
    private final Map<String, AgentMessage> messageById = new ConcurrentHashMap<>();

    private static final Duration LOCAL_RETENTION = Duration.ofHours(12);
    private static final int MAX_LOCAL_WORKFLOWS = 1500;
    private final RedisRetryGate redisGate = new RedisRetryGate(Duration.ofSeconds(30));
    private final Map<String, Instant> touchedAt = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong();

    public AgentMessageBus(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    /** 发送消息：写入内存双索引并异步持久化到 Redis。 */
    public void send(AgentMessage message) {
        if (message == null || message.getWorkflowId() == null || message.getWorkflowId().isBlank()) {
            return;
        }
        messageById.put(message.getMessageId(), message);
        workflowMessages
                .computeIfAbsent(message.getWorkflowId(), key -> Collections.synchronizedList(new ArrayList<>()))
                .add(message);
        touch(message.getWorkflowId());
        cleanupIfDue();
        persist(message.getWorkflowId());
    }

    /** 按 messageId 精确查找消息（O(1) 内存查找）。 */
    public AgentMessage getMessage(String messageId) {
        return messageById.get(messageId);
    }

    /** 获取指定工作流的全部消息（优先内存，缺失时从 Redis 恢复）。 */
    public List<AgentMessage> getWorkflowMessages(String workflowId) {
        List<AgentMessage> local = workflowMessages.get(workflowId);
        if (local != null && !local.isEmpty()) {
            synchronized (local) {
                touch(workflowId);
                return new ArrayList<>(local);
            }
        }
        List<AgentMessage> persisted = loadPersisted(workflowId);
        if (!persisted.isEmpty()) {
            setWorkflowMessages(workflowId, persisted);
            return new ArrayList<>(persisted);
        }
        return Collections.emptyList();
    }

    /** 批量设置工作流消息（清除旧消息后重建索引并持久化）。 */
    public void setWorkflowMessages(String workflowId, List<AgentMessage> messages) {
        clearWorkflow(workflowId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<AgentMessage> safeMessages = Collections.synchronizedList(new ArrayList<>(messages));
        workflowMessages.put(workflowId, safeMessages);
        touch(workflowId);
        synchronized (safeMessages) {
            for (AgentMessage message : safeMessages) {
                messageById.put(message.getMessageId(), message);
            }
        }
        persist(workflowId);
    }

    /** 获取指定工作流中的 Handoff 类型消息（HANDOFF_REQUEST + HANDOFF_RESPONSE）。 */
    public List<AgentMessage> getHandoffMessages(String workflowId) {
        return getWorkflowMessages(workflowId).stream()
                .filter(msg -> msg.getMessageType() == AgentMessage.MessageType.HANDOFF_REQUEST
                        || msg.getMessageType() == AgentMessage.MessageType.HANDOFF_RESPONSE)
                .toList();
    }

    /** 更新指定消息的状态（同步更新内存和 Redis）。 */
    public void updateStatus(String messageId, AgentMessage.MessageStatus status) {
        AgentMessage message = messageById.get(messageId);
        if (message == null) {
            return;
        }
        message.setStatus(status);
        persist(message.getWorkflowId());
    }

    public boolean hasMessage(String messageId) {
        return messageById.containsKey(messageId);
    }

    public int getMessageCount() {
        return messageById.size();
    }

    public int getWorkflowMessageCount(String workflowId) {
        return getWorkflowMessages(workflowId).size();
    }

    /** 清除指定工作流的全部消息（内存 + Redis）。 */
    public void clearWorkflow(String workflowId) {
        List<AgentMessage> messages = workflowMessages.remove(workflowId);
        touchedAt.remove(workflowId);
        if (messages != null) {
            synchronized (messages) {
                messages.forEach(msg -> messageById.remove(msg.getMessageId()));
            }
        }
        deletePersisted(workflowId);
    }

    /** 将工作流消息序列化后写入 Redis（TTL 7天），Redis 不可用时自动降级。 */
    private void persist(String workflowId) {
        if (!redisGate.canAttempt() || workflowId == null || workflowId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(workflowId),
                    objectMapper.writeValueAsString(workflowMessages.getOrDefault(workflowId, List.of()))
            );
            redisTemplate.expire(key(workflowId), TTL);
            redisGate.success();
        } catch (Exception e) {
            redisGate.failure();
            log.warn("Agent message Redis persistence cooling down for 30s | error={}", e.getMessage());
        }
    }

    /** 从 Redis 反序列化工作流消息列表，Redis 不可用时返回空列表。 */
    private List<AgentMessage> loadPersisted(String workflowId) {
        if (!redisGate.canAttempt() || workflowId == null || workflowId.isBlank()) {
            return List.of();
        }
        try {
            String json = redisTemplate.opsForValue().get(key(workflowId));
            if (json == null || json.isBlank()) {
                redisGate.success();
                return List.of();
            }
            List<AgentMessage> messages = objectMapper.readValue(json, new TypeReference<List<AgentMessage>>() {});
            redisGate.success();
            return messages;
        } catch (Exception e) {
            redisGate.failure();
            log.warn("Agent message Redis replay cooling down for 30s | error={}", e.getMessage());
            return List.of();
        }
    }

    /** 从 Redis 删除工作流消息。 */
    private void deletePersisted(String workflowId) {
        if (!redisGate.canAttempt() || workflowId == null || workflowId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(key(workflowId));
            redisGate.success();
        } catch (Exception e) {
            redisGate.failure();
            log.warn("Agent message Redis delete cooling down for 30s | error={}", e.getMessage());
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
            removeLocalMessages(entry.getKey());
            return true;
        });
        if (workflowMessages.size() <= MAX_LOCAL_WORKFLOWS) return;
        touchedAt.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(workflowMessages.size() - MAX_LOCAL_WORKFLOWS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::removeLocal);
    }

    private void removeLocal(String workflowId) {
        removeLocalMessages(workflowId);
        touchedAt.remove(workflowId);
    }

    private void removeLocalMessages(String workflowId) {
        List<AgentMessage> messages = workflowMessages.remove(workflowId);
        if (messages != null) {
            synchronized (messages) {
                messages.forEach(message -> messageById.remove(message.getMessageId()));
            }
        }
    }

    private String key(String workflowId) {
        return KEY_PREFIX + workflowId;
    }
}
