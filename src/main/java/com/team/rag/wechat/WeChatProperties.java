package com.team.rag.wechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 微信配置属性组件。
 *
 * <p>封装所有微信公众号集成相关的配置项，通过 Spring {@code @Value} 注解从
 * application.properties / application.yml 或环境变量中读取。
 *
 * <p>在本学术 RAG 系统中，该组件集中管理微信的以下配置：
 * <ul>
 *   <li>AppID / AppSecret：用于获取 access_token 和调用微信 API；</li>
 *   <li>Token：用于微信服务器回调的签名验证；</li>
 *   <li>AES Key：用于消息加解密（安全模式）；</li>
 *   <li>公开 URL：生成工作流详情页面的访问链接。</li>
 * </ul>
 *
 * <p>该类还提供配置校验方法，用于检查回调配置和回复配置是否完整。
 *
 * @see WeChatSignatureService 签名验证（使用 Token）
 * @see WeChatReplyService 回复服务（使用 AppID/AppSecret）
 */
@Component
public class WeChatProperties {

    /**
     * 微信公众号 AppID。
     * <p>在微信公众平台注册时获得，用于获取 access_token 和调用微信 API。
     */
    @Value("${wechat.app-id:}")
    private String appId;

    /**
     * 微信公众号 AppSecret。
     * <p>与 AppID 配对使用，用于获取 access_token。
     * 属于敏感信息，应注意保护。
     */
    @Value("${wechat.app-secret:}")
    private String appSecret;

    /**
     * 微信服务器配置的 Token。
     * <p>用于验证微信服务器回调请求的签名，确保请求来源的合法性。
     * 在微信公众平台的"服务器配置"中设置。
     */
    @Value("${wechat.token:}")
    private String token;

    /**
     * 消息加解密密钥（EncodingAESKey）。
     * <p>用于微信安全模式下的消息加解密，长度固定为 43 个字符。
     * 在微信公众平台的"服务器配置"中随机生成。
     */
    @Value("${wechat.aes-key:}")
    private String aesKey;

    /**
     * 应用的公开访问基础 URL。
     * <p>用于生成工作流详情页面的公开链接（如通过隧道暴露的地址）。
     * 也作为 Telegram 模块的备选 URL。
     */
    @Value("${wechat.public-app-base-url:}")
    private String publicAppBaseUrl;

    /** 获取微信公众号 AppID */
    public String appId() {
        return appId;
    }

    /** 获取微信公众号 AppSecret */
    public String appSecret() {
        return appSecret;
    }

    /** 获取微信服务器配置的 Token */
    public String token() {
        return token;
    }

    /** 获取消息加解密密钥 */
    public String aesKey() {
        return aesKey;
    }

    /**
     * 获取应用的公开访问基础 URL。
     * <p>未配置时默认返回 http://localhost:8080，自动去除尾部 "/"。
     */
    public String publicAppBaseUrl() {
        return isBlank(publicAppBaseUrl) ? "http://localhost:8080" : trimRight(publicAppBaseUrl, "/");
    }

    /**
     * 检查是否配置了有效的公开访问 URL。
     * <p>排除隧道占位符域名、localhost 和 127.0.0.1。
     *
     * @return 是否配置了有效的公开 URL
     */
    public boolean hasPublicAppBaseUrl() {
        return !isBlank(publicAppBaseUrl)
                && (publicAppBaseUrl.startsWith("https://") || publicAppBaseUrl.startsWith("http://"))
                && !publicAppBaseUrl.contains("your-tunnel-domain")
                && !publicAppBaseUrl.contains("localhost")
                && !publicAppBaseUrl.contains("127.0.0.1");
    }

    /**
     * 检查 AES 密钥是否已配置且长度正确（43 字符）。
     *
     * @return AES 密钥是否有效
     */
    public boolean hasAesKey() {
        return !isBlank(aesKey) && aesKey.length() == 43;
    }

    /**
     * 检查回调配置是否完整（需要 Token）。
     * <p>回调配置用于微信服务器验证请求来源的合法性。
     *
     * @return Token 是否已配置
     */
    public boolean hasCallbackConfig() {
        return !isBlank(token);
    }

    /**
     * 检查回复配置是否完整（需要 AppID 和 AppSecret）。
     * <p>回复配置用于获取 access_token 以调用微信客服消息 API。
     *
     * @return AppID 和 AppSecret 是否都已配置
     */
    public boolean hasReplyConfig() {
        return !isBlank(appId) && !isBlank(appSecret);
    }

    /**
     * 获取回调配置错误信息。
     *
     * @return 如果配置正确返回空字符串，否则返回错误提示
     */
    public String callbackConfigError() {
        return hasCallbackConfig() ? "" : "wechat not configured: wechat.token is required";
    }

    /**
     * 获取回复配置错误信息。
     *
     * @return 如果配置正确返回空字符串，否则返回错误提示
     */
    public String replyConfigError() {
        if (hasReplyConfig()) {
            return "";
        }
        return "wechat reply not configured: wechat.app-id and wechat.app-secret are required";
    }

    /**
     * 获取公开基础 URL 配置错误信息。
     *
     * @return 如果配置正确返回空字符串，否则返回错误提示
     */
    public String publicBaseUrlConfigError() {
        return hasPublicAppBaseUrl()
                ? ""
                : "wechat.public-app-base-url should be the current public tunnel URL";
    }

    /**
     * 获取 AES 密钥配置错误信息。
     *
     * @return 如果配置正确返回空字符串，否则返回错误提示
     */
    public String aesKeyConfigError() {
        return hasAesKey()
                ? ""
                : "wechat.aes-key should be a random 43-character EncodingAESKey from WeChat admin";
    }

    /**
     * 判断字符串是否为 null 或空白。
     *
     * @param value 待检查的字符串
     * @return 是否为空白
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 去除字符串右侧的指定后缀（循环去除直到不再以该后缀结尾）。
     *
     * @param value  原始字符串
     * @param suffix 要去除的后缀
     * @return 去除后缀后的字符串
     */
    private String trimRight(String value, String suffix) {
        String result = value;
        while (result.endsWith(suffix)) {
            result = result.substring(0, result.length() - suffix.length());
        }
        return result;
    }
}
