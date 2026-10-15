package com.team.rag.telegram;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Telegram 消息投递记录。
 *
 * <p>记录一条 Telegram 用户消息从接收到最终回复的完整生命周期信息。
 * 在本学术 RAG 系统中，每条用户消息都会创建一个投递记录，用于：
 * <ul>
 *   <li>追踪消息处理状态（从 WORKFLOW_RUNNING 到 SENT/FAILED）；</li>
 *   <li>记录消息在队列中的流转信息；</li>
 *   <li>保存 Telegram API 的原始响应，便于调试和审计。</li>
 * </ul>
 *
 * <p>该类是一个纯数据载体（POJO），由 {@link TelegramDeliveryStore} 负责创建和管理。
 *
 * @see TelegramDeliveryStore 投递记录存储
 * @see TelegramSendResult 发送结果
 */
public class TelegramDeliveryRecord {

    /** 关联的工作流 ID，将用户消息与 RAG 工作流处理关联起来 */
    public String workflowId;

    /** Telegram 更新 ID（update_id），全局唯一标识一次 Telegram 推送 */
    public long updateId;

    /** Telegram 聊天 ID，标识消息来源的私聊或群组 */
    public long chatId;

    /** 发送消息的 Telegram 用户 ID */
    public long fromId;

    /** Telegram 消息 ID（message_id），在聊天内唯一 */
    public long messageId;

    /** 聊天类型，如 "private"（私聊）、"group"（群组）、"supergroup"（超级群组） */
    public String chatType;

    /** 发送者的 Telegram 用户名（username） */
    public String username;

    /** 用户发送的原始消息文本 */
    public String userMessage;

    /** 解析到的研究组 ID（知识库 ID），决定使用哪个文献库进行 RAG 检索 */
    public String researchGroupId;

    /** 研究组的解析来源，如 USER_BINDING、CHAT_BINDING、SYSTEM_DEFAULT */
    public String researchGroupSource;

    /**
     * 投递状态，取值包括：
     * <ul>
     *   <li>WORKFLOW_RUNNING - 工作流正在处理中</li>
     *   <li>QUEUED - 已加入消息队列等待消费</li>
     *   <li>QUEUE_CONSUMING - 队列消费者正在处理</li>
     *   <li>SENDING - 工作流完成，正在发送结果</li>
     *   <li>SENT - 结果已成功发送</li>
     *   <li>FAILED - 发送失败</li>
     * </ul>
     */
    public String status;

    /** Telegram API 错误码（发送失败时记录） */
    public String errorCode;

    /** Telegram API 错误信息（发送失败时记录） */
    public String errorMessage;

    /** 消息队列模式，如 "redis"、"memory" 等 */
    public String queueMode;

    /** 消息队列中的任务 ID */
    public String queueTaskId;

    /** 消息队列主题/频道名称 */
    public String queueTopic;

    /** 入队失败时的错误信息（当首选队列入队失败时记录降级信息） */
    public String queueError;

    /** 消息分片总数（超长文本会被拆分为多条消息发送） */
    public int chunksTotal;

    /** 已成功发送的分片数 */
    public int chunksSent;

    /** 消息接收时间 */
    public Instant receivedAt;

    /** 消息入队时间 */
    public Instant queuedAt;

    /** 消息出队时间（消费者开始处理的时间） */
    public Instant dequeuedAt;

    /** 工作流完成时间 */
    public Instant completedAt;

    /** 结果投递（发送）时间 */
    public Instant deliveredAt;

    /** 最终答案的预览文本（截取前 240 个字符） */
    public String answerPreview;

    /** 工作流详情页面的公开链接 */
    public String link;

    /** Telegram API 返回的原始 JSON 响应列表（每条分片一个） */
    public final List<String> apiResponses = new ArrayList<>();
}
