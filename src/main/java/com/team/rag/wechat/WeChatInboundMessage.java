package com.team.rag.wechat;

/**
 * 微信收到的消息数据模型。
 *
 * <p>表示从微信服务器回调并解析后的用户消息。
 * 在本学术 RAG 系统中，该类是从微信回调 XML 中提取的核心数据载体，
 * 由 {@link WeChatMessageAdapter} 解析创建，随后传递给
 * {@link WeChatSessionStore} 进行记录，以及 {@link com.team.rag.agent.runtime.WorkflowRunService}
 * 触发工作流。
 *
 * <p>微信回调消息的标准 XML 格式包含以下字段：
 * <pre>{@code
 * <xml>
 *   <ToUserName><![CDATA[公众号ID]]></ToUserName>
 *   <FromUserName><![CDATA[用户OpenID]]></FromUserName>
 *   <CreateTime>1348831860</CreateTime>
 *   <MsgType><![CDATA[text]]></MsgType>
 *   <Content><![CDATA[消息内容]]></Content>
 *   <MsgId>1234567890123456</MsgId>
 * </xml>
 * }</pre>
 *
 * @see WeChatMessageAdapter XML 解析器
 * @see WeChatSessionStore 会话存储
 */
public class WeChatInboundMessage {

    /** 接收方（公众号）的微信号 */
    public String toUserName;

    /** 发送方（用户）的 OpenID，是用户在该公众号下的唯一标识 */
    public String fromUserName;

    /** 消息创建时间（Unix 时间戳字符串，秒级） */
    public String createTime;

    /**
     * 消息类型。
     * <p>常见取值："text"（文本）、"image"（图片）、"voice"（语音）、
     * "video"（视频）、"event"（事件）等。本系统目前只处理 text 类型。
     */
    public String msgType;

    /**
     * 文本消息的内容（仅当 msgType 为 "text" 时有值）。
     * <p>用户发送的问题文本，后续会提交给 RAG 工作流进行处理。
     */
    public String content;

    /** 微信消息 ID（64 位整数的字符串形式） */
    public String msgId;

    /**
     * 判断是否为文本消息。
     *
     * @return 如果消息类型为 "text"（忽略大小写）返回 true
     */
    public boolean isText() {
        return "text".equalsIgnoreCase(msgType);
    }
}
