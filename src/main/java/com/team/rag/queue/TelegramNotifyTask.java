package com.team.rag.queue;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Telegram 通知任务实体类
 *
 * <p>
 * 封装一次 Telegram 通知任务的所有信息，用于在队列（Kafka / 本地线程池）中传递。
 * 支持 JSON 序列化/反序列化，便于 Kafka 消息传输。
 *
 * <p>
 * 任务标识规则：taskId 以 "tgq_" 前缀开头，后接 UUID 前 10 位，
 * 格式如 "tgq_a1b2c3d4e5"，便于日志搜索和问题排查。
 *
 * <p>
 * 使用 @JsonInclude(NON_NULL) 避免序列化 null 字段，减小消息体积。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TelegramNotifyTask {

    /**
     * 任务唯一标识
     * 格式："tgq_" + UUID 前 10 位，如 "tgq_a1b2c3d4e5"
     * 用于日志追踪和投递状态管理
     */
    public String taskId;

    /**
     * Telegram 聊天 ID
     * 标识目标用户或群组，通知将发送到此对话
     */
    public long chatId;

    /**
     * 关联的工作流 ID
     * 将通知任务与 Agent 工作流绑定，用于查询工作流完成状态
     */
    public String workflowId;

    /**
     * 任务来源标识
     * 记录任务是由哪个组件创建的，如 "telegram-poller"
     */
    public String source;

    /**
     * 任务创建时间
     * 使用 Instant 记录精确到纳秒的 UTC 时间，用于超时检测和统计
     */
    public Instant createdAt;

    /**
     * 无参构造函数（JSON 反序列化需要）
     */
    public TelegramNotifyTask() {
    }

    /**
     * 全参构造函数
     *
     * <p>
     * 自动生成 taskId（tgq_ + UUID 前 10 位）和创建时间。
     *
     * @param chatId     Telegram 聊天 ID
     * @param workflowId 关联的工作流 ID
     * @param source     任务来源标识
     */
    public TelegramNotifyTask(long chatId, String workflowId, String source) {
        // 生成唯一任务 ID：tgq_ 前缀 + UUID 前 10 位字符
        this.taskId = "tgq_" + UUID.randomUUID().toString().substring(0, 10);
        this.chatId = chatId;
        this.workflowId = workflowId;
        this.source = source;
        this.createdAt = Instant.now(); // 记录创建时间
    }
}
