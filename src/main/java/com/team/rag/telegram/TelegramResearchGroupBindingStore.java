package com.team.rag.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram 研究组绑定存储。
 *
 * <p>管理 Telegram 用户/聊天与研究组（知识库）之间的绑定关系。
 * 在本学术 RAG 系统中，该组件负责：
 * <ul>
 *   <li>解析消息对应的研究组 ID（resolve 方法）；</li>
 *   <li>创建和管理用户级/聊天级的绑定关系；</li>
 *   <li>维护允许使用的研究组列表。</li>
 * </ul>
 *
 * <p>存储策略采用 Redis + 本地内存的双层架构：
 * <ul>
 *   <li>优先使用 Redis 进行持久化存储，支持多实例共享；</li>
 *   <li>Redis 不可用时自动降级到本地内存存储（ConcurrentHashMap）；</li>
 *   <li>本地内存同时作为读缓存，减少 Redis 访问。</li>
 * </ul>
 *
 * @see TelegramResearchGroupBinding 绑定关系实体
 * @see TelegramResearchGroupResolution 研究组解析结果
 */
@Component
public class TelegramResearchGroupBindingStore {

    private static final Logger log = LoggerFactory.getLogger(TelegramResearchGroupBindingStore.class);

    /** Redis 键前缀，用于存储绑定关系 */
    private static final String KEY_PREFIX = "telegram:binding:";

    /** Redis 集合键，索引所有绑定关系的 key，用于 listBindings 查询 */
    private static final String INDEX_KEY = "telegram:binding:index";

    /** 用户级绑定作用域标识 */
    private static final String SCOPE_USER = "user";

    /** 聊天级绑定作用域标识 */
    private static final String SCOPE_CHAT = "chat";

    /** Redis 操作模板（由 Spring 自动注入） */
    private final StringRedisTemplate redisTemplate;

    /** Jackson JSON 映射器，用于绑定关系的序列化/反序列化 */
    private final ObjectMapper objectMapper;

    /** Telegram 配置属性，获取默认研究组 ID */
    private final TelegramProperties properties;

    /**
     * 本地绑定缓存。
     * <p>key 格式为 "telegram:binding:user:{userId}" 或 "telegram:binding:chat:{chatId}"。
     * 同时作为 Redis 不可用时的降级存储。
     */
    private final Map<String, TelegramResearchGroupBinding> localBindings = new ConcurrentHashMap<>();

    /** Redis 是否可用的标志，初始化时通过 ping 检测 */
    private volatile boolean redisAvailable = false;

    /**
     * 构造函数，注入依赖并测试 Redis 连接。
     *
     * @param redisTemplate Redis 操作模板
     * @param objectMapper  JSON 映射器
     * @param properties    Telegram 配置属性
     */
    public TelegramResearchGroupBindingStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TelegramProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        testRedisConnection();
    }

    /**
     * 解析 Telegram 消息对应的研究组 ID。
     *
     * <p>解析优先级：
     * <ol>
     *   <li>私聊时优先使用用户绑定的默认研究组；</li>
     *   <li>群聊时优先使用聊天绑定的默认研究组；</li>
     *   <li>退回到用户绑定或聊天绑定；</li>
     *   <li>使用系统配置的默认研究组。</li>
     * </ol>
     *
     * @param message 收到的 Telegram 消息
     * @return 研究组解析结果，包含最终的研究组 ID 和来源信息
     */
    public TelegramResearchGroupResolution resolve(TelegramInboundMessage message) {
        TelegramResearchGroupResolution resolution = new TelegramResearchGroupResolution();
        resolution.telegramUserId = message.fromId;
        resolution.telegramChatId = message.chatId;
        resolution.chatType = message.chatType;

        TelegramResearchGroupBinding userBinding = getUserBinding(message.fromId);
        TelegramResearchGroupBinding chatBinding = getChatBinding(message.chatId);
        resolution.userBinding = userBinding;
        resolution.chatBinding = chatBinding;

        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(properties.researchGroupId());
        addAllowed(allowed, userBinding);
        addAllowed(allowed, chatBinding);
        resolution.allowedResearchGroupIds = allowed;

        if (isPrivate(message.chatType) && hasDefault(userBinding)) {
            resolution.researchGroupId = userBinding.defaultResearchGroupId;
            resolution.source = "USER_BINDING";
        } else if (!isPrivate(message.chatType) && hasDefault(chatBinding)) {
            resolution.researchGroupId = chatBinding.defaultResearchGroupId;
            resolution.source = "CHAT_BINDING";
        } else if (hasDefault(userBinding)) {
            resolution.researchGroupId = userBinding.defaultResearchGroupId;
            resolution.source = "USER_BINDING";
        } else if (hasDefault(chatBinding)) {
            resolution.researchGroupId = chatBinding.defaultResearchGroupId;
            resolution.source = "CHAT_BINDING";
        } else {
            resolution.researchGroupId = properties.researchGroupId();
            resolution.source = "SYSTEM_DEFAULT";
        }

        resolution.allowedResearchGroupIds.add(resolution.researchGroupId);
        return resolution;
    }

    /**
     * 为 Telegram 用户绑定默认研究组。
     *
     * @param telegramUserId  Telegram 用户 ID
     * @param researchGroupId 要绑定的研究组 ID
     * @param updatedBy       操作者标识
     * @return 更新后的绑定关系
     * @throws IllegalArgumentException 如果 researchGroupId 为空
     */
    public TelegramResearchGroupBinding bindUser(long telegramUserId, String researchGroupId, String updatedBy) {
        TelegramResearchGroupBinding binding = getUserBinding(telegramUserId);
        if (binding == null) {
            binding = new TelegramResearchGroupBinding();
            binding.scope = SCOPE_USER;
            binding.telegramUserId = telegramUserId;
            binding.createdAt = Instant.now();
        }
        return updateDefault(binding, researchGroupId, updatedBy);
    }

    /**
     * 为 Telegram 聊天/群组绑定默认研究组。
     *
     * @param telegramChatId  Telegram 聊天 ID
     * @param researchGroupId 要绑定的研究组 ID
     * @param updatedBy       操作者标识
     * @return 更新后的绑定关系
     * @throws IllegalArgumentException 如果 researchGroupId 为空
     */
    public TelegramResearchGroupBinding bindChat(long telegramChatId, String researchGroupId, String updatedBy) {
        TelegramResearchGroupBinding binding = getChatBinding(telegramChatId);
        if (binding == null) {
            binding = new TelegramResearchGroupBinding();
            binding.scope = SCOPE_CHAT;
            binding.telegramChatId = telegramChatId;
            binding.createdAt = Instant.now();
        }
        return updateDefault(binding, researchGroupId, updatedBy);
    }

    /**
     * 为 Telegram 用户添加允许使用的研究组。
     *
     * @param telegramUserId  Telegram 用户 ID
     * @param researchGroupId 要添加的研究组 ID
     * @param updatedBy       操作者标识
     * @return 更新后的绑定关系
     */
    public TelegramResearchGroupBinding allowForUser(long telegramUserId, String researchGroupId, String updatedBy) {
        TelegramResearchGroupBinding binding = getUserBinding(telegramUserId);
        if (binding == null) {
            binding = new TelegramResearchGroupBinding();
            binding.scope = SCOPE_USER;
            binding.telegramUserId = telegramUserId;
            binding.createdAt = Instant.now();
        }
        return addAllowedBinding(binding, researchGroupId, updatedBy);
    }

    /**
     * 为 Telegram 聊天/群组添加允许使用的研究组。
     *
     * @param telegramChatId  Telegram 聊天 ID
     * @param researchGroupId 要添加的研究组 ID
     * @param updatedBy       操作者标识
     * @return 更新后的绑定关系
     */
    public TelegramResearchGroupBinding allowForChat(long telegramChatId, String researchGroupId, String updatedBy) {
        TelegramResearchGroupBinding binding = getChatBinding(telegramChatId);
        if (binding == null) {
            binding = new TelegramResearchGroupBinding();
            binding.scope = SCOPE_CHAT;
            binding.telegramChatId = telegramChatId;
            binding.createdAt = Instant.now();
        }
        return addAllowedBinding(binding, researchGroupId, updatedBy);
    }

    /**
     * 获取用户的绑定关系。
     *
     * @param telegramUserId Telegram 用户 ID
     * @return 绑定关系，如果不存在返回 null
     */
    public TelegramResearchGroupBinding getUserBinding(long telegramUserId) {
        return getBinding(userKey(telegramUserId));
    }

    /**
     * 获取聊天/群组的绑定关系。
     *
     * @param telegramChatId Telegram 聊天 ID
     * @return 绑定关系，如果不存在返回 null
     */
    public TelegramResearchGroupBinding getChatBinding(long telegramChatId) {
        return getBinding(chatKey(telegramChatId));
    }

    /**
     * 列出所有绑定关系。
     * <p>合并本地缓存和 Redis 中的所有绑定记录。
     *
     * @return 所有绑定关系的列表
     */
    public List<TelegramResearchGroupBinding> listBindings() {
        Set<String> keys = new LinkedHashSet<>(localBindings.keySet());
        if (redisAvailable) {
            try {
                Set<String> redisKeys = redisTemplate.opsForSet().members(INDEX_KEY);
                if (redisKeys != null) {
                    keys.addAll(redisKeys);
                }
            } catch (Exception e) {
                log.warn("Telegram binding Redis index read failed: {}", e.getMessage());
                redisAvailable = false;
            }
        }

        List<TelegramResearchGroupBinding> result = new ArrayList<>();
        for (String key : keys) {
            TelegramResearchGroupBinding binding = getBinding(key);
            if (binding != null) {
                result.add(binding);
            }
        }
        return result;
    }

    /** Redis 是否可用 */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }

    /**
     * 获取当前存储模式。
     *
     * @return "redis" 或 "memory"
     */
    public String storageMode() {
        return redisAvailable ? "redis" : "memory";
    }

    /**
     * 更新绑定关系的默认研究组，并保存。
     *
     * @param binding         绑定关系
     * @param researchGroupId 新的默认研究组 ID
     * @param updatedBy       操作者标识
     * @return 更新后的绑定关系
     */
    private TelegramResearchGroupBinding updateDefault(
            TelegramResearchGroupBinding binding,
            String researchGroupId,
            String updatedBy) {
        if (isBlank(researchGroupId)) {
            throw new IllegalArgumentException("researchGroupId is required");
        }
        binding.defaultResearchGroupId = researchGroupId.trim();
        return addAllowedBinding(binding, researchGroupId, updatedBy);
    }

    /**
     * 向绑定关系添加允许的研究组 ID 并保存。
     *
     * @param binding         绑定关系
     * @param researchGroupId 要添加的研究组 ID
     * @param updatedBy       操作者标识
     * @return 更新后的绑定关系
     */
    private TelegramResearchGroupBinding addAllowedBinding(
            TelegramResearchGroupBinding binding,
            String researchGroupId,
            String updatedBy) {
        if (isBlank(researchGroupId)) {
            throw new IllegalArgumentException("researchGroupId is required");
        }
        if (binding.allowedResearchGroupIds == null) {
            binding.allowedResearchGroupIds = new LinkedHashSet<>();
        }
        binding.allowedResearchGroupIds.add(researchGroupId.trim());
        binding.updatedAt = Instant.now();
        binding.updatedBy = updatedBy;
        binding.normalize();
        save(binding);
        return binding;
    }

    /**
     * 保存绑定关系到本地缓存和 Redis（如果可用）。
     *
     * @param binding 绑定关系
     */
    private void save(TelegramResearchGroupBinding binding) {
        String key = keyFor(binding);
        localBindings.put(key, binding);
        if (redisAvailable) {
            try {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(binding));
                redisTemplate.opsForSet().add(INDEX_KEY, key);
            } catch (Exception e) {
                log.warn("Telegram binding Redis write failed, falling back to memory: {}", e.getMessage());
                redisAvailable = false;
            }
        }
    }

    /**
     * 根据 key 获取绑定关系（优先从本地缓存读取，缓存未命中时从 Redis 读取）。
     *
     * @param key 绑定关系的存储 key
     * @return 绑定关系，如果不存在返回 null
     */
    private TelegramResearchGroupBinding getBinding(String key) {
        TelegramResearchGroupBinding local = localBindings.get(key);
        if (local != null) {
            return local;
        }
        if (redisAvailable) {
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json == null || json.isBlank()) {
                    return null;
                }
                TelegramResearchGroupBinding binding = objectMapper.readValue(json, TelegramResearchGroupBinding.class);
                binding.normalize();
                localBindings.put(key, binding);
                return binding;
            } catch (Exception e) {
                log.warn("Telegram binding Redis read failed, falling back to memory: {}", e.getMessage());
                redisAvailable = false;
            }
        }
        return null;
    }

    /**
     * 测试 Redis 连接是否可用。
     * <p>在构造函数中调用，通过 PING 命令检测 Redis 连通性。
     */
    private void testRedisConnection() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            redisAvailable = true;
            log.info("Telegram research group binding store uses Redis");
        } catch (Exception e) {
            redisAvailable = false;
            log.warn("Telegram research group binding store uses memory fallback: {}", e.getMessage());
        }
    }

    /**
     * 将绑定关系中的允许研究组 ID 集合合并到目标集合中。
     *
     * @param allowed  目标集合
     * @param binding  绑定关系
     */
    private void addAllowed(Set<String> allowed, TelegramResearchGroupBinding binding) {
        if (binding == null || binding.allowedResearchGroupIds == null) {
            return;
        }
        allowed.addAll(binding.allowedResearchGroupIds);
    }

    /**
     * 检查绑定关系是否设置了默认研究组。
     */
    private boolean hasDefault(TelegramResearchGroupBinding binding) {
        return binding != null && !isBlank(binding.defaultResearchGroupId);
    }

    /**
     * 判断是否为私聊。
     */
    private boolean isPrivate(String chatType) {
        return "private".equalsIgnoreCase(chatType);
    }

    /**
     * 根据绑定关系的 scope 生成存储 key。
     */
    private String keyFor(TelegramResearchGroupBinding binding) {
        if (SCOPE_CHAT.equalsIgnoreCase(binding.scope)) {
            return chatKey(binding.telegramChatId);
        }
        return userKey(binding.telegramUserId);
    }

    /** 生成用户级绑定的 Redis key */
    private String userKey(long telegramUserId) {
        return KEY_PREFIX + SCOPE_USER + ":" + telegramUserId;
    }

    /** 生成聊天级绑定的 Redis key */
    private String chatKey(long telegramChatId) {
        return KEY_PREFIX + SCOPE_CHAT + ":" + telegramChatId;
    }

    /** 判断字符串是否为 null 或空白 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
