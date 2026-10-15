package com.team.rag.telegram;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Telegram 研究组解析结果。
 *
 * <p>表示系统为一条 Telegram 消息解析出的研究组（知识库）绑定信息。
 * 在本学术 RAG 系统中，研究组决定了使用哪个文献库进行 RAG 检索。
 *
 * <p>解析优先级（由 {@link TelegramResearchGroupBindingStore#resolve} 实现）：
 * <ol>
 *   <li>私聊时优先使用用户绑定（USER_BINDING）；</li>
 *   <li>群聊时优先使用聊天绑定（CHAT_BINDING）；</li>
 *   <li>如果没有对应的绑定，退回到用户绑定或聊天绑定；</li>
 *   <li>如果都没有绑定，使用系统默认研究组（SYSTEM_DEFAULT）。</li>
 * </ol>
 *
 * <p>该类是一个纯数据载体（POJO），由 {@link TelegramResearchGroupBindingStore} 创建。
 *
 * @see TelegramResearchGroupBindingStore 绑定存储（负责解析逻辑）
 * @see TelegramResearchGroupBinding 绑定关系实体
 */
public class TelegramResearchGroupResolution {

    /**
     * 最终解析出的研究组 ID。
     * <p>该值将被传递给工作流运行服务，决定使用哪个知识库进行 RAG 检索。
     */
    public String researchGroupId;

    /**
     * 研究组的解析来源。
     * <p>取值：
     * <ul>
     *   <li>"USER_BINDING" - 来自用户级绑定</li>
     *   <li>"CHAT_BINDING" - 来自聊天/群组级绑定</li>
     *   <li>"SYSTEM_DEFAULT" - 使用系统默认值</li>
     * </ul>
     */
    public String source;

    /** 消息发送者的 Telegram 用户 ID */
    public long telegramUserId;

    /** 消息来源的 Telegram 聊天 ID */
    public long telegramChatId;

    /** 聊天类型（如 "private"、"group"） */
    public String chatType;

    /**
     * 当前消息允许使用的研究组 ID 集合。
     * <p>包含系统默认研究组、用户绑定中允许的研究组、聊天绑定中允许的研究组。
     * 用于前端展示和权限控制。
     */
    public Set<String> allowedResearchGroupIds = new LinkedHashSet<>();

    /** 用户级绑定关系（如果存在） */
    public TelegramResearchGroupBinding userBinding;

    /** 聊天/群组级绑定关系（如果存在） */
    public TelegramResearchGroupBinding chatBinding;
}
