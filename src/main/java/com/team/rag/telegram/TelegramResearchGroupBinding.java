package com.team.rag.telegram;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Telegram 研究组绑定关系。
 *
 * <p>表示 Telegram 用户或聊天与研究组（知识库）之间的绑定关系。
 * 在本学术 RAG 系统中，每个 Telegram 用户或群组聊天可以绑定一个默认研究组，
 * 系统在处理该用户/群组的消息时会使用绑定的知识库进行 RAG 检索。
 *
 * <p>绑定分为两种作用域（scope）：
 * <ul>
 *   <li>user - 用户级绑定，根据 Telegram 用户 ID 关联</li>
 *   <li>chat - 聊天级绑定，根据 Telegram 聊天 ID 关联（适用于群组）</li>
 * </ul>
 *
 * <p>每个绑定关系还维护一个允许使用的研究组 ID 列表（allowedResearchGroupIds），
 * 用户可以通过 /use 或 /allow 命令管理。
 *
 * @see TelegramResearchGroupBindingStore 绑定存储和管理
 * @see TelegramResearchGroupResolution 研究组解析结果
 */
public class TelegramResearchGroupBinding {

    /**
     * 绑定作用域。
     * <p>取值：
     * <ul>
     *   <li>"user" - 用户级绑定，按 telegramUserId 关联</li>
     *   <li>"chat" - 聊天级绑定，按 telegramChatId 关联</li>
     * </ul>
     */
    public String scope;

    /** Telegram 用户 ID（scope 为 "user" 时使用） */
    public long telegramUserId;

    /** Telegram 聊天 ID（scope 为 "chat" 时使用） */
    public long telegramChatId;

    /**
     * 默认研究组 ID。
     * <p>当系统为该用户/聊天解析研究组时，优先使用此值。
     */
    public String defaultResearchGroupId;

    /**
     * 允许使用的研究组 ID 集合。
     * <p>用户可以通过 /allow 命令添加额外的研究组到此列表中。
     * 默认研究组也会被自动添加到此集合中。
     * 使用 LinkedHashSet 保持插入顺序且去重。
     */
    public Set<String> allowedResearchGroupIds = new LinkedHashSet<>();

    /** 绑定关系创建时间 */
    public Instant createdAt;

    /** 绑定关系最后更新时间 */
    public Instant updatedAt;

    /** 最后更新者的标识（如 "telegram:user:12345"） */
    public String updatedBy;

    /**
     * 规范化绑定数据。
     *
     * <p>执行以下操作：
     * <ol>
     *   <li>确保 allowedResearchGroupIds 不为 null；</li>
     *   <li>将默认研究组 ID 添加到允许列表中；</li>
     *   <li>去除所有 ID 的首尾空白；</li>
     *   <li>过滤掉 null 和空白的 ID。</li>
     * </ol>
     * <p>该方法在每次保存绑定前由 {@link TelegramResearchGroupBindingStore} 调用。
     */
    public void normalize() {
        // 确保允许列表不为 null
        if (allowedResearchGroupIds == null) {
            allowedResearchGroupIds = new LinkedHashSet<>();
        }
        // 默认研究组必须在允许列表中
        if (defaultResearchGroupId != null && !defaultResearchGroupId.isBlank()) {
            defaultResearchGroupId = defaultResearchGroupId.trim();
            allowedResearchGroupIds.add(defaultResearchGroupId);
        }
        // 清理允许列表：去除空白并过滤无效值
        Set<String> cleaned = new LinkedHashSet<>();
        for (String id : allowedResearchGroupIds) {
            if (id != null && !id.isBlank()) {
                cleaned.add(id.trim());
            }
        }
        allowedResearchGroupIds = cleaned;
    }
}
