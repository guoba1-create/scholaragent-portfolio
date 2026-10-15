package com.team.rag.wechat;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信消息投递记录。
 *
 * <p>记录一条微信用户消息从接收到最终回复的完整生命周期信息。
 * 在本学术 RAG 系统中，每条用户消息都会创建一个投递记录，用于：
 * <ul>
 *   <li>追踪消息处理状态（从 WORKFLOW_RUNNING 到 SENT/FAILED）；</li>
 *   <li>记录消息分片发送的进度；</li>
 *   <li>保存微信客服消息 API 的原始响应，便于调试和审计。</li>
 * </ul>
 *
 * <p>注意：openId 字段使用 @JsonIgnore 注解，避免在 API 响应中泄露用户隐私。
 * 对外展示使用 openIdMasked（脱敏后的值）。
 *
 * @see WeChatSessionStore 会话存储（负责创建和管理投递记录）
 * @see WeChatSendResult 发送结果
 */
public class WeChatDeliveryRecord {

    /** 关联的工作流 ID */
    public String workflowId;

    /** 微信用户的 OpenID（使用 @JsonIgnore 防止序列化泄露） */
    @JsonIgnore
    public String openId;

    /** 脱敏后的 OpenID（前4位 + **** + 后4位），用于日志和展示 */
    public String openIdMasked;

    /** 微信消息 ID（MsgId） */
    public String msgId;

    /** 用户发送的原始消息文本 */
    public String userMessage;

    /**
     * 投递状态，取值包括：
     * <ul>
     *   <li>WORKFLOW_RUNNING - 工作流正在处理中</li>
     *   <li>SENDING - 工作流完成，正在发送结果</li>
     *   <li>SENT - 结果已成功发送</li>
     *   <li>FAILED - 发送失败</li>
     * </ul>
     */
    public String status;

    /** 微信 API 错误码（发送失败时记录） */
    public String errorCode;

    /** 微信 API 错误信息（发送失败时记录） */
    public String errorMessage;

    /** 消息分片总数（超长文本会被拆分为多条客服消息发送） */
    public int chunksTotal;

    /** 已成功发送的分片数 */
    public int chunksSent;

    /** 消息接收时间 */
    public Instant receivedAt;

    /** 工作流完成时间 */
    public Instant completedAt;

    /** 结果投递（发送）时间 */
    public Instant deliveredAt;

    /** 最终答案的预览文本（截取前 240 个字符） */
    public String answerPreview;

    /** 工作流详情页面的公开链接 */
    public String link;

    /** 微信客服消息 API 返回的原始 JSON 响应列表（每条分片一个） */
    public final List<String> apiResponses = new ArrayList<>();
}
