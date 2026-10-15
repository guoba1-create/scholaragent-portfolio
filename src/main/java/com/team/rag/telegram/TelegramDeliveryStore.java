package com.team.rag.telegram;

import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Telegram 投递记录存储。
 *
 * <p>管理所有 Telegram 消息投递记录的内存存储组件。在本学术 RAG 系统中，
 * 该组件负责：
 * <ul>
 *   <li>记录用户消息的接收和处理过程；</li>
 *   <li>追踪每条消息从接收到发送的完整状态流转；</li>
 *   <li>提供基于 updateId 的消息去重查询（防止重复处理）；</li>
 *   <li>维护最近投递记录的有序列表，支持运维查看。</li>
 * </ul>
 *
 * <p>所有数据存储在内存中的 ConcurrentHashMap 中，重启后清空。
 * 使用并发安全的集合以支持多线程同时读写。
 *
 * @see TelegramDeliveryRecord 投递记录实体
 * @see TelegramSendResult 发送结果
 * @see WorkflowRunSnapshot 工作流运行快照
 */
@Component
public class TelegramDeliveryStore {

    /**
     * Telegram 更新 ID 到工作流 ID 的映射。
     * <p>用于消息去重：通过 updateId 快速判断该消息是否已被处理过，
     * 防止 Long Polling 重复拉取导致重复触发工作流。
     */
    private final Map<Long, String> workflowByUpdateId = new ConcurrentHashMap<>();

    /**
     * 工作流 ID 到投递记录的映射。
     * <p>核心存储结构，保存每个工作流对应的完整投递记录。
     */
    private final Map<String, TelegramDeliveryRecord> deliveryByWorkflow = new ConcurrentHashMap<>();

    /**
     * 最近处理的工作流 ID 双端队列。
     * <p>按时间倒序排列（最新在前），用于支持运维人员查看最近的投递记录。
     * 通过 {@link #trimRecent()} 方法自动清理超过 200 条的旧记录。
     */
    private final Deque<String> recentWorkflows = new ConcurrentLinkedDeque<>();

    /**
     * 根据 Telegram 更新 ID 查找对应的工作流 ID。
     *
     * @param updateId Telegram 更新 ID
     * @return 工作流 ID，如果该 updateId 未被记录则返回 null
     */
    public String findWorkflowByUpdateId(long updateId) {
        return workflowByUpdateId.get(updateId);
    }

    /**
     * 记录一条收到的用户消息，创建新的投递记录。
     *
     * <p>该方法在用户消息到达时调用，完成以下操作：
     * <ol>
     *   <li>建立 updateId 到 workflowId 的映射（用于去重）；</li>
     *   <li>创建投递记录并填充用户消息信息；</li>
     *   <li>记录研究组解析结果；</li>
     *   <li>将记录存入最近列表。</li>
     * </ol>
     *
     * @param message    收到的 Telegram 消息
     * @param workflowId 关联的工作流 ID
     * @param resolution 研究组解析结果（可能为 null）
     * @return 新创建的投递记录
     */
    public TelegramDeliveryRecord recordInbound(
            TelegramInboundMessage message,
            String workflowId,
            TelegramResearchGroupResolution resolution) {
        // 建立 updateId -> workflowId 映射，用于去重检查
        workflowByUpdateId.put(message.updateId, workflowId);

        // 创建并填充投递记录
        TelegramDeliveryRecord record = new TelegramDeliveryRecord();
        record.workflowId = workflowId;
        record.updateId = message.updateId;
        record.chatId = message.chatId;
        record.fromId = message.fromId;
        record.messageId = message.messageId;
        record.chatType = message.chatType;
        record.username = message.username;
        record.userMessage = message.text;
        if (resolution != null) {
            record.researchGroupId = resolution.researchGroupId;
            record.researchGroupSource = resolution.source;
        }
        record.status = "WORKFLOW_RUNNING";
        record.receivedAt = Instant.now();
        deliveryByWorkflow.put(workflowId, record);
        // 将 workflowId 添加到最近列表头部
        recentWorkflows.addFirst(workflowId);
        trimRecent();
        return record;
    }

    /**
     * 标记工作流已完成处理，准备发送结果。
     *
     * @param workflowId 工作流 ID
     * @param snapshot   工作流运行快照（包含最终答案）
     */
    public void markWorkflowComplete(String workflowId, WorkflowRunSnapshot snapshot) {
        TelegramDeliveryRecord record = deliveryByWorkflow.get(workflowId);
        if (record == null || snapshot == null) {
            return;
        }
        record.completedAt = Instant.now();
        record.answerPreview = preview(snapshot.finalAnswer);
        record.status = "SENDING";
    }

    /**
     * 标记消息已入队。
     *
     * @param workflowId 工作流 ID
     * @param mode       队列模式（如 redis、memory）
     * @param taskId     队列中的任务 ID
     * @param topic      队列主题/频道名称
     */
    public void markQueued(String workflowId, String mode, String taskId, String topic) {
        TelegramDeliveryRecord record = deliveryByWorkflow.get(workflowId);
        if (record == null) {
            return;
        }
        record.status = "QUEUED";
        record.queueMode = mode;
        record.queueTaskId = taskId;
        record.queueTopic = topic;
        record.queueError = null;
        record.queuedAt = Instant.now();
    }

    /**
     * 标记队列入队失败并记录降级信息。
     * <p>当首选队列（如 Redis）入队失败时，系统可能降级到备选队列（如内存队列）。
     * 此方法仅记录错误信息，不改变投递状态。
     *
     * @param workflowId  工作流 ID
     * @param failedMode  失败的队列模式
     * @param error       错误描述
     */
    public void markQueueFallback(String workflowId, String failedMode, String error) {
        TelegramDeliveryRecord record = deliveryByWorkflow.get(workflowId);
        if (record == null) {
            return;
        }
        record.queueError = failedMode + " enqueue failed: " + error;
    }

    /**
     * 标记消息已从队列出队，开始被消费者处理。
     *
     * @param workflowId 工作流 ID
     * @param mode       队列模式
     * @param taskId     队列任务 ID
     * @param dequeuedAt 出队时间（如果为 null 则使用当前时间）
     */
    public void markDequeued(String workflowId, String mode, String taskId, Instant dequeuedAt) {
        TelegramDeliveryRecord record = deliveryByWorkflow.get(workflowId);
        if (record == null) {
            return;
        }
        record.status = "QUEUE_CONSUMING";
        record.queueMode = mode;
        record.queueTaskId = taskId;
        record.dequeuedAt = dequeuedAt == null ? Instant.now() : dequeuedAt;
    }

    /**
     * 标记消息已成功发送到 Telegram。
     *
     * @param workflowId 工作流 ID
     * @param result     发送结果
     * @param link       工作流详情页面链接
     */
    public void markSent(String workflowId, TelegramSendResult result, String link) {
        TelegramDeliveryRecord record = deliveryByWorkflow.get(workflowId);
        if (record == null) {
            return;
        }
        record.status = "SENT";
        record.deliveredAt = Instant.now();
        record.link = link;
        copyResult(record, result);
    }

    /**
     * 标记消息发送失败。
     *
     * @param workflowId 工作流 ID
     * @param result     发送结果（包含错误信息）
     * @param link       工作流详情页面链接
     */
    public void markFailed(String workflowId, TelegramSendResult result, String link) {
        TelegramDeliveryRecord record = deliveryByWorkflow.get(workflowId);
        if (record == null) {
            return;
        }
        record.status = "FAILED";
        record.deliveredAt = Instant.now();
        record.link = link;
        copyResult(record, result);
    }

    /**
     * 根据工作流 ID 获取投递记录。
     *
     * @param workflowId 工作流 ID
     * @return 投递记录，如果不存在则返回 null
     */
    public TelegramDeliveryRecord getDelivery(String workflowId) {
        return deliveryByWorkflow.get(workflowId);
    }

    /**
     * 获取最近的投递记录列表。
     *
     * @param limit 最大返回条数（会被限制在 1-100 范围内）
     * @return 最近的投递记录列表，按时间倒序排列
     */
    public List<TelegramDeliveryRecord> recentDeliveries(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<TelegramDeliveryRecord> result = new ArrayList<>();
        for (String workflowId : recentWorkflows) {
            TelegramDeliveryRecord record = deliveryByWorkflow.get(workflowId);
            if (record != null) {
                result.add(record);
                if (result.size() >= safeLimit) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 将发送结果的数据复制到投递记录中。
     *
     * @param record 投递记录
     * @param result 发送结果
     */
    private void copyResult(TelegramDeliveryRecord record, TelegramSendResult result) {
        if (result == null) {
            return;
        }
        record.chunksTotal = result.chunksTotal;
        record.chunksSent = result.chunksSent;
        record.errorCode = result.errorCode;
        record.errorMessage = result.errorMessage;
        // 同步复制 API 响应列表，避免并发修改异常
        synchronized (record.apiResponses) {
            record.apiResponses.clear();
            record.apiResponses.addAll(result.apiResponses);
        }
    }

    /**
     * 清理超过 200 条的旧记录。
     * <p>从双端队列尾部移除最旧的 workflowId，保持列表大小在限制范围内。
     */
    private void trimRecent() {
        while (recentWorkflows.size() > 200) {
            recentWorkflows.pollLast();
        }
    }

    /**
     * 生成文本预览（截取前 240 个字符）。
     *
     * @param text 原始文本
     * @return 截取后的预览文本，超过 240 字符时追加 "..."
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }
}
