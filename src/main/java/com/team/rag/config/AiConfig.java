package com.team.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

/**
 * 统一配置类 —— 集中管理所有 AI 相关的 Bean
 *
 * <p>
 * 核心职责：
 * <ul>
 * <li>Pinecone 向量存储（文献库 + 个人研究记忆共用同一索引，通过 Metadata 隔离）</li>
 * <li>ChatMemoryProvider：基于 Redis 持久化的对话历史管理</li>
 * </ul>
 *
 * <p>
 * 注意：QwenStreamingChatModel、QwenChatModel、QwenEmbeddingModel
 * 已通过 langchain4j-community-dashscope-spring-boot-starter 自动装配，无需手动定义 Bean。
 */
@Configuration
public class AiConfig {

    /** 日志记录器，用于记录配置初始化过程中的关键事件 */
    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    // ======================== Pinecone 配置参数 ========================

    /**
     * Pinecone API 密钥，用于身份验证
     * 配置项：pinecone.api-key
     */
    @Value("${pinecone.api-key}")
    private String pineconeApiKey;

    /**
     * Pinecone 索引名称，指向目标向量索引
     * 配置项：pinecone.index-name
     */
    @Value("${pinecone.index-name}")
    private String pineconeIndexName;

    /**
     * Pinecone 命名空间，用于在同一个索引内进行数据隔离
     * 配置项：pinecone.namespace，默认值 "default"
     */
    @Value("${pinecone.namespace:default}")
    private String pineconeNamespace;

    /**
     * 是否启用 Pinecone 向量存储
     * 设为 false 时将回退到内存存储（数据不持久化），适用于本地开发环境
     * 配置项：pinecone.enabled，默认值 true
     */
    @Value("${pinecone.enabled:true}")
    private boolean pineconeEnabled;

    // ======================== 短期记忆窗口大小 ========================

    /**
     * 对话历史滑动窗口大小，即保留最近多少条消息
     * 值越大上下文越长，但 token 消耗越多
     * 配置项：literature-rag.memory-window-size，默认值 20
     */
    @Value("${literature-rag.memory-window-size:20}")
    private int memoryWindowSize;

    /**
     * Pinecone 向量存储 Bean
     *
     * <p>
     * 文献库文档和个人研究记忆存储在同一个 Pinecone 索引中，
     * 通过 Metadata（researchGroupId / userId）进行严格的数据隔离。
     * 查询时必须附加 MetadataFilter，绝不允许全局盲查！
     *
     * <p>启动时会进行三级降级策略：
     * <ol>
     * <li>如果配置禁用了 Pinecone（pinecone.enabled=false），直接使用内存存储</li>
     * <li>如果 DNS 无法解析 api.pinecone.io，回退到内存存储</li>
     * <li>如果连接 Pinecone 时发生异常，回退到内存存储</li>
     * </ol>
     *
     * @return PineconeEmbeddingStore 或 InMemoryEmbeddingStore（降级时）
     */
    @Bean
    public EmbeddingStore<TextSegment> pineconeEmbeddingStore() {
        // 第一级降级：配置显式禁用
        if (!pineconeEnabled) {
            log.info("Pinecone disabled via config (pinecone.enabled=false), using InMemoryEmbeddingStore");
            return new InMemoryEmbeddingStore<>();
        }

        // 第二级降级：DNS 预检查，避免创建无法连接的 gRPC 通道
        if (!isPineconeReachable()) {
            log.warn("============================================================");
            log.warn("Pinecone DNS unreachable (api.pinecone.io)");
            log.warn("Falling back to InMemoryEmbeddingStore (data will NOT persist)");
            log.warn("To suppress this warning, set pinecone.enabled=false");
            log.warn("============================================================");
            return new InMemoryEmbeddingStore<>();
        }

        try {
            // 构建 Pinecone 向量存储客户端
            EmbeddingStore<TextSegment> store = PineconeEmbeddingStore.builder()
                    .apiKey(pineconeApiKey)         // API 密钥
                    .index(pineconeIndexName)       // 目标索引名
                    .nameSpace(pineconeNamespace)   // 命名空间
                    .build();
            log.info("PineconeEmbeddingStore initialized | index={}, namespace={}", pineconeIndexName, pineconeNamespace);
            return store;
        } catch (Exception e) {
            // 第三级降级：连接异常时回退到内存存储
            log.warn("============================================================");
            log.warn("FAILED to connect to Pinecone: {}", e.getMessage());
            log.warn("Falling back to InMemoryEmbeddingStore (data will NOT persist)");
            log.warn("To suppress this warning, set pinecone.enabled=false");
            log.warn("============================================================");
            return new InMemoryEmbeddingStore<>();
        }
    }

    /**
     * DNS 预检查：验证 api.pinecone.io 是否可达
     *
     * @return true 表示 DNS 解析成功，false 表示不可达
     */
    private boolean isPineconeReachable() {
        try {
            InetAddress.getByName("api.pinecone.io");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ChatMemoryProvider —— 基于 Redis 持久化的对话历史管理
     *
     * <p>
     * 使用 RedisChatMemoryStore 实现对话历史的持久化存储：
     * <ul>
     * <li>服务重启后可恢复对话上下文</li>
     * <li>支持分布式部署，多实例共享对话历史</li>
     * <li>自动过期机制（24小时），避免存储无限增长</li>
     * </ul>
     *
     * <p>
     * 每个用户拥有独立的滑动窗口，窗口大小由
     * literature-rag.memory-window-size 控制（默认保留最近 20 条消息）。
     *
     * @param chatMemoryStore Redis 持久化存储实现（由 Spring 自动注入）
     * @return ChatMemoryProvider 实例，根据 memoryId 创建独立的对话记忆
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)                   // 用户唯一标识
                .maxMessages(memoryWindowSize)  // 滑动窗口大小
                .chatMemoryStore(chatMemoryStore) // Redis 持久化存储
                .build();
    }

    /**
     * 获取或创建指定用户的 ChatMemory 实例（兼容旧代码）
     *
     * <p>
     * 这不是一个 Spring Bean，而是一个工具方法，供 LiteratureDialogueService 使用。
     * 使用 Redis 持久化后，ChatMemory 会自动从 Redis 加载历史消息。
     *
     * @param memoryId  用户标识（通常是 userId）
     * @param store     Redis 存储实例
     * @return ChatMemory 实例
     */
    public ChatMemory getOrCreateChatMemory(Object memoryId, ChatMemoryStore store) {
        return MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(memoryWindowSize)
                .chatMemoryStore(store)
                .build();
    }
}
