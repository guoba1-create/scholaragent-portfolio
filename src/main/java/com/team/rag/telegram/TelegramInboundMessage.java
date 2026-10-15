package com.team.rag.telegram;

/**
 * Telegram 收到的消息数据模型。
 *
 * <p>表示从 Telegram API 拉取并解析后的用户消息。
 * 在本学术 RAG 系统中，该类是从 Telegram Update JSON 中提取的核心数据载体，
 * 由 {@link TelegramUpdatePoller} 在收到消息时解析创建，随后传递给
 * {@link TelegramDeliveryStore} 进行记录，以及 {@link WorkflowRunService} 触发工作流。
 *
 * <p>所有字段使用 public 修饰，直接由解析逻辑赋值，采用 POJO 模式而非 getter/setter。
 *
 * @see TelegramUpdatePoller 消息轮询器（负责解析创建）
 * @see TelegramDeliveryStore 投递记录存储（负责使用和存储）
 */
public class TelegramInboundMessage {

    /**
     * Telegram 更新 ID（update_id）。
     * <p>由 Telegram 服务器分配的全局唯一递增编号，用于标识一次更新推送。
     * 同时也用于 Long Polling 的 offset 管理和消息去重。
     */
    public long updateId;

    /** Telegram 消息 ID（message_id），在单个聊天内唯一 */
    public long messageId;

    /** 消息来源的聊天 ID，可以是私聊用户的 ID 或群组/超级群组的 ID */
    public long chatId;

    /** 发送者的 Telegram 用户 ID */
    public long fromId;

    /**
     * 聊天类型。
     * <p>常见取值：
     * <ul>
     *   <li>"private" - 私聊</li>
     *   <li>"group" - 普通群组</li>
     *   <li>"supergroup" - 超级群组</li>
     *   <li>"channel" - 频道</li>
     * </ul>
     * 系统根据此字段决定是否需要 @mention 才响应（群聊需要，私聊不需要）。
     */
    public String chatType;

    /** 发送者的 Telegram 用户名（username），可能为空 */
    public String username;

    /** 发送者的名字（first_name） */
    public String firstName;

    /**
     * 消息的文本内容。
     * <p>用户发送的原始文本，可能包含 /ask 命令前缀或 @botname 提及。
     * 后续会由 TelegramUpdatePoller 进行规范化处理（去除命令前缀和 @mention）。
     */
    public String text;
}
