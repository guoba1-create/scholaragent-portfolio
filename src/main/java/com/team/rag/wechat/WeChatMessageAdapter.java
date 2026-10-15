package com.team.rag.wechat;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/**
 * 微信消息适配器。
 *
 * <p>负责将微信服务器发送的 XML 回调消息解析为 {@link WeChatInboundMessage} 对象。
 * 在本学术 RAG 系统中，该组件是微信消息通道的数据解析层，位于 HTTP Controller
 * 和业务逻辑之间。
 *
 * <p>安全特性：
 * <ul>
 *   <li>禁用 DTD 声明（disallow-doctype-decl），防止 XXE 攻击；</li>
 *   <li>禁用外部实体引用（external-general-entities、external-parameter-entities）；</li>
 *   <li>禁用 XInclude 和实体展开。</li>
 * </ul>
 *
 * @see WeChatInboundMessage 解析后的消息模型
 * @see WeChatSignatureService 签名验证（验证回调来源的合法性）
 */
@Component
public class WeChatMessageAdapter {

    /**
     * 将微信回调的 XML 字符串解析为消息对象。
     *
     * @param xml 微信服务器发送的 XML 回调内容
     * @return 解析后的微信消息对象
     * @throws IllegalArgumentException 如果 XML 为空或解析失败
     */
    public WeChatInboundMessage parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("Empty WeChat callback body");
        }
        try {
            // 创建安全的 XML 解析器工厂，禁用所有 XXE 相关特性
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // 禁用 DTD
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false); // 禁用外部通用实体
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); // 禁用外部参数实体
            factory.setXIncludeAware(false); // 禁用 XInclude
            factory.setExpandEntityReferences(false); // 禁用实体展开

            // 解析 XML 文档
            Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            document.getDocumentElement().normalize();

            // 提取各个字段
            WeChatInboundMessage message = new WeChatInboundMessage();
            message.toUserName = text(document, "ToUserName");
            message.fromUserName = text(document, "FromUserName");
            message.createTime = text(document, "CreateTime");
            message.msgType = text(document, "MsgType");
            message.content = text(document, "Content");
            message.msgId = text(document, "MsgId");
            return message;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse WeChat XML message", e);
        }
    }

    /**
     * 从 XML 文档中提取指定标签的文本内容。
     *
     * @param document XML 文档对象
     * @param tagName  标签名
     * @return 标签的文本内容，如果标签不存在返回空字符串
     */
    private String text(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        if (nodes == null || nodes.getLength() == 0 || nodes.item(0) == null) {
            return "";
        }
        return nodes.item(0).getTextContent();
    }
}
