package com.team.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.team.rag.entity.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 对话管理服务 —— 负责 RAG 系统中用户对话的全生命周期管理。
 *
 * <h3>职责说明</h3>
 * <ul>
 *   <li>对话 CRUD：创建、查询（单个/列表）、保存、删除、清空</li>
 *   <li>消息管理：向已有对话追加消息（role + content）</li>
 *   <li>索引维护：通过 Redis ZSet 维护用户维度的对话列表，按最近活跃时间排序</li>
 * </ul>
 *
 * <h3>Redis 存储设计（双 Key 结构）</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ Key 1: conversation:{userId}:{conversationId}   (String 类型)   │
 * │        → 只存储对话元数据，TTL 30 天                            │
 * │ Key 2: conversation:messages:{userId}:{conversationId} (List)  │
 * │        → Lua 原子追加消息，避免并发读改写丢失                    │
 * │                                                                  │
 * │ Key 2: conversation:list:{userId}               (ZSet 类型)     │
 * │        → member = conversationId, score = 毫秒时间戳            │
 * │        → 用于按时间倒序列出用户的对话列表                        │
 * └──────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计决策</h3>
 * <ul>
 *   <li>采用 String + ZSet 双 Key 而非 Hash：因为对话 JSON 较大且需要整体读写</li>
 *   <li>不设降级策略：Redis 不可用时直接抛异常，避免数据不一致的静默错误</li>
 *   <li>TTL 30 天：自动清理长期不活跃的对话，防止存储无限增长</li>
 *   <li>每次追加消息都刷新 ZSet score：保证活跃对话排在列表前面</li>
 * </ul>
 *
 * @see com.team.rag.entity.Conversation
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 对话详情键前缀，完整格式: conversation:{userId}:{conversationId} */
    private static final String KEY_CONVERSATION = "conversation:";

    /** 用户对话列表键前缀，完整格式: conversation:list:{userId}（ZSet） */
    private static final String KEY_CONVERSATION_LIST = "conversation:list:";

    private static final String KEY_CONVERSATION_MESSAGES = "conversation:messages:";

    private static final String KEY_CONVERSATION_SEQUENCE = "conversation:sequence:";

    private static final String KEY_CONVERSATION_MIGRATION = "conversation:migrated:";

    /** 对话详情的过期时间（天），过期后自动从 Redis 删除，防止历史数据无限累积 */
    private static final long CONVERSATION_EXPIRE_DAYS = 30;

    private static final long CONVERSATION_EXPIRE_SECONDS = TimeUnit.DAYS.toSeconds(CONVERSATION_EXPIRE_DAYS);

    private static final DefaultRedisScript<Long> APPEND_MESSAGE_SCRIPT = new DefaultRedisScript<>("""
            local seq = redis.call('INCR', KEYS[1])
            local row = '{\"sequence\":' .. seq .. ',\"role\":' .. ARGV[1]
                .. ',\"content\":' .. ARGV[2] .. ',\"timestamp\":' .. ARGV[3] .. '}'
            redis.call('RPUSH', KEYS[2], row)
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            redis.call('EXPIRE', KEYS[2], ARGV[4])
            redis.call('ZADD', KEYS[3], ARGV[5], ARGV[6])
            redis.call('EXPIRE', KEYS[3], ARGV[4])
            return seq
            """, Long.class);

    /** Redis 操作模板（Spring 自动注入），所有 Redis 读写都通过它完成 */
    private final StringRedisTemplate redisTemplate;

    /** JSON serialization for conversation metadata and append-only message rows. */
    private final ObjectMapper objectMapper;

    /**
     * 构造方法，由 Spring 容器自动注入 StringRedisTemplate。
     *
     * <p>同时初始化 ObjectMapper 并注册 JavaTimeModule，
     * 使得 LocalDateTime 等 Java 8 时间类型能正确序列化为 ISO-8601 格式字符串，
     * 而不是默认的时间戳数字（可读性更好）。</p>
     *
     * @param redisTemplate Spring 自动注入的 Redis 操作模板
     */
    public ConversationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        // 注册 Java 8 时间模块，使 LocalDateTime 能被正确序列化
        this.objectMapper.registerModule(new JavaTimeModule());
        // 禁用"时间戳"序列化模式，改为输出 ISO-8601 格式（如 "2024-01-01T12:00:00"）
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 创建一个新的对话，并同时写入对话详情和用户索引。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>生成不带横杠的 UUID 作为 conversationId（32 位十六进制字符串）</li>
     *   <li>构造 Conversation 对象并保存到 Redis（详情键）</li>
     *   <li>将 conversationId 加入用户索引 ZSet（便于按时间排序查询列表）</li>
     * </ol>
     *
     * @param userId  用户唯一标识
     * @param title   对话标题（通常为用户的首条消息或系统生成的摘要）
     * @return 创建成功的 Conversation 对象（包含生成的 conversationId）
     */
    public Conversation createConversation(String userId, String title) {
        // 生成 32 位随机 ID（去掉 UUID 中的横杠，更紧凑）
        String conversationId = UUID.randomUUID().toString().replace("-", "");
        Conversation conversation = new Conversation(conversationId, userId, title);

        saveConversation(conversation);       // 写入详情键
        addToUserList(userId, conversationId); // 写入索引键

        log.info("创建新会话 | userId={}, conversationId={}", userId, conversationId);
        return conversation;
    }

    /**
     * 将对话完整对象序列化为 JSON 并保存到 Redis（覆盖写）。
     *
     * <p>每次保存都会重置 30 天的过期计时器，因此活跃对话不会被自动清理。
     * 这是一个设计取舍：用"最后活跃时间"来决定何时淘汰旧数据。</p>
     *
     * <p>注意：此方法是"覆盖写"，调用前必须先通过 {@link #getConversation} 获取最新版本，
     * 修改后再调用此方法保存，否则会丢失未读取的修改。</p>
     *
     * @param conversation 要保存的对话对象
     * @throws RuntimeException 如果 JSON 序列化失败（极端情况，通常不会发生）
     */
    public void saveConversation(Conversation conversation) {
        try {
            // 拼接 Redis key: conversation:{userId}:{conversationId}
            String key = KEY_CONVERSATION + conversation.getUserId() + ":" + conversation.getId();
            // Messages live in an append-only Redis list. Persist only mutable metadata here.
            Conversation metadata = metadataOnly(conversation);
            String json = objectMapper.writeValueAsString(metadata);

            // 写入 Redis 并设置 30 天过期；set 会覆盖旧值
            redisTemplate.opsForValue().set(key, json, CONVERSATION_EXPIRE_DAYS, TimeUnit.DAYS);

            log.debug("保存会话元数据 | key={}", key);
        } catch (JsonProcessingException e) {
            log.error("序列化会话失败", e);
            // 序列化失败是程序错误，不设降级，直接抛出异常让调用方感知
            throw new RuntimeException("保存会话失败", e);
        }
    }

    /**
     * 根据用户 ID 和对话 ID 从 Redis 获取单个对话。
     *
     * <p>查询路径：Redis String key → JSON 字符串 → Conversation 对象</p>
     *
     * @param userId         用户唯一标识
     * @param conversationId 对话唯一标识
     * @return 对话对象；如果不存在（key 已过期或从未创建）或反序列化失败，返回 null
     */
    public Conversation getConversation(String userId, String conversationId) {
        try {
            String key = KEY_CONVERSATION + userId + ":" + conversationId;
            // 从 Redis 读取 JSON 字符串；key 不存在时返回 null
            String json = redisTemplate.opsForValue().get(key);

            if (json == null) {
                return null;
            }

            Conversation conversation = objectMapper.readValue(json, Conversation.class);
            migrateLegacyMessagesIfNeeded(conversation);
            List<Conversation.Message> messages = readMessages(userId, conversationId);
            conversation.setMessages(messages);
            if (!messages.isEmpty()) {
                conversation.setUpdatedAt(messages.get(messages.size() - 1).getTimestamp());
            }
            return conversation;
        } catch (JsonProcessingException e) {
            // 反序列化失败（数据格式损坏），返回 null 而非抛异常，让调用方自行处理
            log.error("反序列化会话失败", e);
            return null;
        }
    }

    /**
     * 获取指定用户的所有对话列表，按最近活跃时间倒序排列。
     *
     * <p>实现原理：</p>
     * <ol>
     *   <li>从 ZSet 中按 score（毫秒时间戳）倒序取出所有 conversationId</li>
     *   <li>逐个根据 conversationId 从 Redis 获取完整的 Conversation 对象</li>
     *   <li>跳过已过期或已删除的对话（getConversation 返回 null）</li>
     * </ol>
     *
     * <p>性能注意：此方法会产生 N+1 次 Redis 调用（1 次 ZRANGE + N 次 GET），
     * 对话数量较多时可能有性能瓶颈。如果未来对话量增大，可考虑
     * 使用 Redis Pipeline 或 MGET 批量优化。</p>
     *
     * @param userId 用户唯一标识
     * @return 对话列表（按最近活跃时间倒序）；用户无对话时返回空列表
     */
    public List<Conversation> getUserConversations(String userId) {
        String listKey = KEY_CONVERSATION_LIST + userId;
        // reverseRange = 倒序获取，score 大的（时间最近的）排在前面
        // 参数 (0, -1) 表示获取全部元素
        Set<String> conversationIds = redisTemplate.opsForZSet().reverseRange(listKey, 0, -1);

        List<Conversation> conversations = new ArrayList<>();

        if (conversationIds != null) {
            // 遍历每个 conversationId，逐个从 Redis 获取完整对话对象
            for (String conversationId : conversationIds) {
                Conversation conversation = getConversation(userId, conversationId);
                if (conversation != null) {
                    conversations.add(conversation);
                }
                // conversation 为 null 说明详情键已过期但索引还在（TTL 竞态），跳过即可
            }
        }

        log.debug("获取用户会话列表 | userId={}, count={}", userId, conversations.size());
        return conversations;
    }

    /**
     * 向指定对话追加一条消息，并将该对话刷新到列表最前面。
     *
     * <p>消息通过 Lua 脚本原子完成序号递增、列表追加、TTL 刷新和活跃索引更新，
     * 不再读取并覆盖整段对话 JSON，因此并发写入不会互相覆盖。</p>
     *
     * <p>追加消息的同时会更新 ZSet 的 score 为当前时间戳，
     * 使得这个对话在列表中排到最前面（最近活跃的排最前）。</p>
     *
     * @param userId         用户唯一标识
     * @param conversationId 对话唯一标识
     * @param role           消息角色，通常为 "user" 或 "assistant"
     * @param content        消息文本内容
     */
    public void addMessage(String userId, String conversationId, String role, String content) {
        String metadataKey = conversationKey(userId, conversationId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(metadataKey))) {
            log.warn("会话不存在 | userId={}, conversationId={}", userId, conversationId);
            return;
        }
        appendMessageAtomic(userId, conversationId, new Conversation.Message(role, content));
        redisTemplate.expire(metadataKey, CONVERSATION_EXPIRE_DAYS, TimeUnit.DAYS);
        log.debug("原子追加消息 | userId={}, conversationId={}, role={}", userId, conversationId, role);
    }

    /**
     * 删除单个对话（同时删除详情数据和列表索引）。
     *
     * <p>删除操作涉及两个 Redis Key：</p>
     * <ul>
     *   <li>详情键 conversation:{userId}:{conversationId} — 存储对话内容</li>
     *   <li>索引键 conversation:list:{userId} 中对应的 member</li>
     * </ul>
     * <p>两个 Key 必须同时清理，否则会出现"索引指向不存在的对话"这种脏数据。</p>
     *
     * @param userId         用户唯一标识
     * @param conversationId 要删除的对话唯一标识
     */
    public void deleteConversation(String userId, String conversationId) {
        // 删除对话详情键（conversation:{userId}:{conversationId}）
        String key = KEY_CONVERSATION + userId + ":" + conversationId;
        redisTemplate.delete(List.of(
                key,
                messageKey(userId, conversationId),
                sequenceKey(userId, conversationId),
                migrationKey(userId, conversationId)));

        // 从用户索引 ZSet 中移除该 conversationId
        String listKey = KEY_CONVERSATION_LIST + userId;
        redisTemplate.opsForZSet().remove(listKey, conversationId);

        log.info("删除会话 | userId={}, conversationId={}", userId, conversationId);
    }

    /**
     * 清空指定用户的全部对话（批量删除所有详情键 + 删除整个索引键）。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>从 ZSet 中获取该用户的所有 conversationId</li>
     *   <li>逐个删除对应的对话详情键</li>
     *   <li>最后删除整个 ZSet 索引键</li>
     * </ol>
     *
     * <p>注意：此方法不是原子操作，如果中途 Redis 连接断开，可能出现部分删除的情况。
     * 对于当前业务规模可接受；如需强一致性，可考虑使用 Lua 脚本或 Redis 事务。</p>
     *
     * @param userId 用户唯一标识
     */
    public void clearUserConversations(String userId) {
        String listKey = KEY_CONVERSATION_LIST + userId;
        // 先获取所有 conversationId（正序/倒序无所谓，反正都要删）
        Set<String> conversationIds = redisTemplate.opsForZSet().range(listKey, 0, -1);

        if (conversationIds != null) {
            // 逐个删除每个对话的详情键
            for (String conversationId : conversationIds) {
                redisTemplate.delete(List.of(
                        conversationKey(userId, conversationId),
                        messageKey(userId, conversationId),
                        sequenceKey(userId, conversationId),
                        migrationKey(userId, conversationId)));
            }
        }

        // 最后删除整个索引键（一次性清理）
        redisTemplate.delete(listKey);

        log.info("清空用户会话 | userId={}", userId);
    }

    private Conversation metadataOnly(Conversation source) {
        Conversation metadata = new Conversation();
        metadata.setId(source.getId());
        metadata.setUserId(source.getUserId());
        metadata.setTitle(source.getTitle());
        metadata.setCreatedAt(source.getCreatedAt());
        metadata.setUpdatedAt(source.getUpdatedAt());
        metadata.setMessages(new ArrayList<>());
        return metadata;
    }

    private void appendMessageAtomic(String userId, String conversationId, Conversation.Message message) {
        try {
            String roleJson = objectMapper.writeValueAsString(message.getRole());
            String contentJson = objectMapper.writeValueAsString(message.getContent());
            String timestampJson = objectMapper.writeValueAsString(message.getTimestamp().toString());
            Long sequence = redisTemplate.execute(
                    APPEND_MESSAGE_SCRIPT,
                    List.of(
                            sequenceKey(userId, conversationId),
                            messageKey(userId, conversationId),
                            KEY_CONVERSATION_LIST + userId),
                    roleJson,
                    contentJson,
                    timestampJson,
                    Long.toString(CONVERSATION_EXPIRE_SECONDS),
                    Long.toString(System.currentTimeMillis()),
                    conversationId);
            if (sequence == null) {
                throw new IllegalStateException("Redis did not return a message sequence");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize conversation message", e);
        }
    }

    private List<Conversation.Message> readMessages(String userId, String conversationId) {
        List<String> rows = redisTemplate.opsForList().range(messageKey(userId, conversationId), 0, -1);
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<Conversation.Message> result = new ArrayList<>(rows.size());
        for (String row : rows) {
            try {
                StoredMessage stored = objectMapper.readValue(row, StoredMessage.class);
                Conversation.Message message = new Conversation.Message();
                message.setRole(stored.role());
                message.setContent(stored.content());
                message.setTimestamp(stored.timestamp() == null ? java.time.Instant.now() : stored.timestamp());
                result.add(message);
            } catch (Exception e) {
                log.warn("跳过损坏的会话消息 | userId={}, conversationId={}, error={}",
                        userId, conversationId, e.getMessage());
            }
        }
        return result;
    }

    private void migrateLegacyMessagesIfNeeded(Conversation conversation) {
        List<Conversation.Message> legacy = conversation.getMessages();
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        String userId = conversation.getUserId();
        String conversationId = conversation.getId();
        Boolean migrationOwner = redisTemplate.opsForValue().setIfAbsent(
                migrationKey(userId, conversationId),
                "1",
                CONVERSATION_EXPIRE_DAYS,
                TimeUnit.DAYS);
        if (Boolean.TRUE.equals(migrationOwner)) {
            for (Conversation.Message message : legacy) {
                appendMessageAtomic(userId, conversationId, message);
            }
            conversation.setMessages(new ArrayList<>());
            saveConversation(conversation);
            log.info("Migrated legacy conversation messages | userId={}, conversationId={}, count={}",
                    userId, conversationId, legacy.size());
        }
    }

    private String conversationKey(String userId, String conversationId) {
        return KEY_CONVERSATION + userId + ":" + conversationId;
    }

    private String messageKey(String userId, String conversationId) {
        return KEY_CONVERSATION_MESSAGES + userId + ":" + conversationId;
    }

    private String sequenceKey(String userId, String conversationId) {
        return KEY_CONVERSATION_SEQUENCE + userId + ":" + conversationId;
    }

    private String migrationKey(String userId, String conversationId) {
        return KEY_CONVERSATION_MIGRATION + userId + ":" + conversationId;
    }

    private record StoredMessage(long sequence, String role, String content, java.time.Instant timestamp) {
    }

    /**
     * 将对话 ID 添加到用户的对话索引 ZSet 中。
     *
     * <p>ZSet 的 score 使用当前毫秒时间戳，这样在 {@link #getUserConversations}
     * 中按 score 倒序排列时，最近创建/活跃的对话会排在最前面。</p>
     *
     * <p>如果 conversationId 已存在于 ZSet 中，ZADD 命令会更新其 score（Redis 的默认行为），
     * 因此此方法也可用于刷新对话的排序位置。</p>
     *
     * @param userId         用户唯一标识
     * @param conversationId 对话唯一标识
     */
    private void addToUserList(String userId, String conversationId) {
        String listKey = KEY_CONVERSATION_LIST + userId;
        // ZADD：如果 member 已存在则更新 score，否则新增；score = 当前毫秒时间戳
        redisTemplate.opsForZSet().add(listKey, conversationId, System.currentTimeMillis());
    }
}
