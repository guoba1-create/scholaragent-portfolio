package com.team.rag.wechat;

import java.util.ArrayList;
import java.util.List;

/**
 * 微信消息发送结果。
 *
 * <p>封装通过微信客服消息接口发送消息后的结果信息。
 * 在本学术 RAG 系统中，当工作流处理完成需要将结果回复给微信用户时，
 * 发送操作的结果通过该类返回。
 *
 * <p>支持消息分片发送场景：当消息超过微信单条客服消息限制时，
 * 系统会自动拆分为多条消息依次发送，通过 chunksTotal 和 chunksSent
 * 跟踪分片发送进度。
 *
 * @see WeChatReplyService 发送消息的服务
 * @see WeChatDeliveryRecord 投递记录（包含发送结果信息）
 */
public class WeChatSendResult {

    /** 是否发送成功 */
    public boolean success;

    /** 消息分片总数（超长文本会被拆分为多条客服消息） */
    public int chunksTotal;

    /** 已成功发送的分片数 */
    public int chunksSent;

    /** 微信 API 错误码（如 40001、45015 等），发送成功时为 null */
    public String errorCode;

    /** 微信 API 错误描述信息，发送成功时为 null */
    public String errorMessage;

    /** 微信客服消息 API 返回的原始 JSON 响应列表（每个分片一条响应） */
    public final List<String> apiResponses = new ArrayList<>();

    /**
     * 创建一个发送失败的结果对象（静态工厂方法）。
     *
     * <p>用于在未实际调用微信 API 就已知会失败的场景，
     * 例如 AppID/AppSecret 未配置时。
     *
     * @param errorCode    错误码（如 "CONFIG" 表示配置错误）
     * @param errorMessage 错误描述信息
     * @return 包含失败信息的发送结果
     */
    public static WeChatSendResult failure(String errorCode, String errorMessage) {
        WeChatSendResult result = new WeChatSendResult();
        result.success = false;
        result.errorCode = errorCode;
        result.errorMessage = errorMessage;
        return result;
    }
}
