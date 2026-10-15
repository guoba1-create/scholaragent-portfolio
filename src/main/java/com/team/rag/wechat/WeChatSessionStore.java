package com.team.rag.wechat;

import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 微信会话存储。
 *
 * <p>管理微信用户会话和消息投递记录的内存存储组件。
 * 在本学术 RAG 系统中，该组件负责：
 * <ul>
 *   <li>维护用户 OpenID 与工作流 ID 的关联关系；</li>
 *   <li>追踪每条消息从接收到发送的完整状态流转；</li>
 *   <li>提供基于消息 ID 的去重查询（防止重复处理）；</li>
 *   <li>维护最近投递记录的有序列表，支持运维查看。</li>
 * </ul>
 *
 * <p>所有数据存储在内存中的 ConcurrentHashMap 中，重启后清空。
 * 使用并发安全的集合以支持多线程同时读写。
 *
 * @see WeChatDeliveryRecord 投递记录实体
 * @see WeChatSendResult 发送结果
 * @see WorkflowRunSnapshot 工作流运行快照
 */
@Component
public class WeChatSessionStore {

    /**
     * OpenID 到工作流 ID 列表的映射。
     * <p>记录每个微信用户关联的所有工作流 ID，用于查看用户的历史会话。
     * value 使用 Collections.synchronizedList 保证线程安全。
     */
    private final Map<String, List<String>> workflowsByOpenId = new ConcurrentHashMap<>();

    /**
     * 工作流 ID 到 OpenID 的反向映射。
     * <p>用于通过工作流 ID 快速查找对应的微信用户。
     */
    private final Map<String, String> openIdByWorkflow = new ConcurrentHashMap<>();

    /** 工作流 ID 到创建时间的映射 */
    private final Map<String, Instant> createdAtByWorkflow = new ConcurrentHashMap<>();

    /**
     * 工作流 ID 到投递记录的映射。
     * <p>核心存储结构，保存每个工作流对应的完整投递记录。
     */
    private final Map<String, WeChatDeliveryRecord> deliveryByWorkflow = new ConcurrentHashMap<>();

    /**
     * 消息 ID 到工作流 ID 的映射。
     * <p>key 格式为 "{openId}:{msgId}"，用于消息去重：
     * 通过微信消息 ID 快速判断该消息是否已被处理过。
     */
    private final Map<String, String> workflowByMessage = new ConcurrentHashMap<>();

    /**
     * 最近处理的工作流 ID 双端队列。
     * <p>按时间倒序排列（最新在前），用于支持运维人员查看最近的投递记录。
     * 通过 {@link #trimRecent()} 方法自动清理超过 200 条的旧记录。
     */
    private final Deque<String> recentWorkflows = new ConcurrentLinkedDeque<>();

    /**
     * 记录一个工作流与用户 OpenID 的关联关系。
     *
     * @param openId     微信用户的 OpenID
     * @param workflowId 工作流 ID
     */
    public void record(String openId, String workflowId) {
        workflowsByOpenId.computeIfAbsent(openId, key -> Collections.synchronizedList(new ArrayList<>()))
                .add(workflowId);
        openIdByWorkflow.put(workflowId, openId);
        createdAtByWorkflow.put(workflowId, Instant.now());
    }

    /**
     * 记一条收到的用户消息，创建新的投递记录。
     *
     * <p>该方法在用户消息到达时调用，完成以下操作：
     * <ol>
     *   <li>记录 OpenID 与 workflowId 的关联；</li>
     *   <li>建立消息 ID 到 workflowId 的映射（用于去重）；</li>
     *   <li>创建投递记录并填充消息信息；</li>
     *   <li>将记录存入最近列表。</li>
     * </ol>
     *
     * @param openId       微信用户的 OpenID
     * @param msgId        微信消息 ID
     * @param userMessage  用户发送的文本内容
     * @param workflowId   关联的工作流 ID
     * @return 新创建的投递记录
     */
    public WeChatDeliveryRecord recordInbound(String openId, String msgId, String userMessage, String workflowId) {
        record(openId, workflowId);
        if (msgId != null && !msgId.isBlank()) {
            workflowByMessage.put(messageKey(openId, msgId), workflowId);
        }

        WeChatDeliveryRecord record = new WeChatDeliveryRecord();
        record.workflowId = workflowId;
        record.openId = openId;
        record.openIdMasked = mask(openId);
        record.msgId = msgId;
        record.userMessage = userMessage;
        record.status = "WORKFLOW_RUNNING";
        record.receivedAt = Instant.now();
        deliveryByWorkflow.put(workflowId, record);
        recentWorkflows.addFirst(workflowId);
        trimRecent();
        return record;
    }

    /**
     * 根据微信消息 ID 查找对应的工作流 ID。
     *
     * @param openId 微信用户的 OpenID
     * @param msgId  微信消息 ID
     * @return 工作流 ID，如果该消息未被记录则返回 null
     */
    public String findWorkflowByMessage(String openId, String msgId) {
        if (openId == null || openId.isBlank() || msgId == null || msgId.isBlank()) {
            return null;
        }
        return workflowByMessage.get(messageKey(openId, msgId));
    }

    /**
     * 标记工作流已完成处理，准备发送结果。
     *
     * @param workflowId 工作流 ID
     * @param snapshot   工作流运行快照
     */
    public void markWorkflowComplete(String workflowId, WorkflowRunSnapshot snapshot) {
        WeChatDeliveryRecord record = deliveryByWorkflow.get(workflowId);
        if (record == null || snapshot == null) {
            return;
        }
        record.completedAt = Instant.now();
        record.answerPreview = preview(snapshot.finalAnswer);
        record.status = "SENDING";
    }

    /**
     * 标记消息已成功发送到微信。
     *
     * @param workflowId 工作流 ID
     * @param result     发送结果
     * @param link       工作流详情页面链接
     */
    public void markSent(String workflowId, WeChatSendResult result, String link) {
        WeChatDeliveryRecord record = deliveryByWorkflow.get(workflowId);
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
    public void markFailed(String workflowId, WeChatSendResult result, String link) {
        WeChatDeliveryRecord record = deliveryByWorkflow.get(workflowId);
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
    public WeChatDeliveryRecord getDelivery(String workflowId) {
        return deliveryByWorkflow.get(workflowId);
    }

    /**
     * 获取最近的投递记录列表。
     *
     * @param limit 最大返回条数（会被限制在 1-100 范围内）
     * @return 最近的投递记录列表，按时间倒序排列
     */
    public List<WeChatDeliveryRecord> recentDeliveries(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<WeChatDeliveryRecord> result = new ArrayList<>();
        for (String workflowId : recentWorkflows) {
            WeChatDeliveryRecord record = deliveryByWorkflow.get(workflowId);
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
     * 获取指定用户关联的所有工作流 ID。
     *
     * @param openId 微信用户的 OpenID
     * @return 工作流 ID 列表，如果用户无记录返回空列表
     */
    public List<String> getWorkflows(String openId) {
        List<String> values = workflowsByOpenId.get(openId);
        if (values == null) {
            return List.of();
        }
        synchronized (values) {
            return new ArrayList<>(values);
        }
    }

    /**
     * 根据工作流 ID 获取关联的用户 OpenID。
     *
     * @param workflowId 工作流 ID
     * @return 用户 OpenID，如果不存在则返回 null
     */
    public String getOpenId(String workflowId) {
        return openIdByWorkflow.get(workflowId);
    }

    /**
     * 获取工作流的创建时间。
     *
     * @param workflowId 工作流 ID
     * @return 创建时间，如果不存在则返回 null
     */
    public Instant getCreatedAt(String workflowId) {
        return createdAtByWorkflow.get(workflowId);
    }

    /**
     * 将发送结果的数据复制到投递记录中。
     *
     * @param record 投递记录
     * @param result 发送结果
     */
    private void copyResult(WeChatDeliveryRecord record, WeChatSendResult result) {
        if (result == null) {
            return;
        }
        record.chunksTotal = result.chunksTotal;
        record.chunksSent = result.chunksSent;
        record.errorCode = result.errorCode;
        record.errorMessage = result.errorMessage;
        synchronized (record.apiResponses) {
            record.apiResponses.clear();
            record.apiResponses.addAll(result.apiResponses);
        }
    }

    /**
     * 生成消息去重的复合键。
     *
     * @param openId 微信用户 OpenID
     * @param msgId  微信消息 ID
     * @return 复合键字符串
     */
    private String messageKey(String openId, String msgId) {
        return openId + ":" + msgId;
    }

    /**
     * 清理超过 200 条的旧记录。
     */
    private void trimRecent() {
        while (recentWorkflows.size() > 200) {
            recentWorkflows.pollLast();
        }
    }

    /**
     * 对字符串进行脱敏处理（前4位 + **** + 后4位）。
     *
     * @param value 原始字符串
     * @return 脱敏后的字符串
     */
    private String mask(String value) {
        if (value == null || value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /**
     * 生成文本预览（截取前 240 个字符）。
     *
     * @param text 原始文本
     * @return 截取后的预览文本
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }
}
