package com.team.rag.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Telegram 配置属性组件。
 *
 * <p>封装所有 Telegram 集成相关的配置项，通过 Spring {@code @Value} 注解从
 * application.properties / application.yml 或环境变量中读取。
 *
 * <p>在本学术 RAG 系统中，该组件集中管理 Telegram Bot 的以下配置：
 * <ul>
 *   <li>Bot Token：用于调用 Telegram Bot API 的身份凭证；</li>
 *   <li>Long Polling 参数：轮询超时、批量大小、启动时跳过旧消息等；</li>
 *   <li>代理设置：支持通过 HTTP/SOCKS4/SOCKS5 代理访问 Telegram API；</li>
 *   <li>应用链接：生成工作流详情页面的公开访问 URL；</li>
 *   <li>默认研究组：未绑定时使用的默认知识库 ID。</li>
 * </ul>
 *
 * <p>该类还提供配置校验方法（hasBotToken、hasProxyConfig 等）和错误提示方法，
 * 用于在启动时或运行时检查配置是否完整。
 *
 * @see TelegramBotClient Bot API 客户端
 * @see TelegramUpdatePoller 消息轮询器
 */
@Component
public class TelegramProperties {

    /**
     * Telegram Bot Token。
     * <p>从 BotFather 获取的 Bot 身份凭证，格式为 "数字ID:随机字符串"。
     * 可通过环境变量 TELEGRAM_BOT_TOKEN 或配置项 telegram.bot-token 设置。
     */
    @Value("${telegram.bot-token:}")
    private String botToken;

    /**
     * 是否启用 Long Polling 模式。
     * <p>设为 true 时系统会启动后台线程持续拉取 Telegram 消息。
     * 设为 false 时不启动轮询（适用于使用 Webhook 或仅用于发送通知的场景）。
     */
    @Value("${telegram.polling-enabled:false}")
    private boolean pollingEnabled;

    /**
     * Long Polling 超时时间（秒）。
     * <p>调用 getUpdates 时的长轮询超时：在此时间内无新消息则返回空列表。
     * Telegram API 限制最大值为 50 秒，会被限制在 1-50 范围内。
     */
    @Value("${telegram.polling-timeout-seconds:25}")
    private int pollingTimeoutSeconds;

    /**
     * Long Polling 单次拉取的最大消息数。
     * <p>Telegram API 限制最大值为 100，会被限制在 1-100 范围内。
     */
    @Value("${telegram.polling-limit:20}")
    private int pollingLimit;

    /**
     * 启动时是否跳过旧的未处理消息。
     * <p>设为 true 时，系统启动后会先拉取并丢弃所有积压的旧消息，
     * 只处理启动后收到的新消息，避免重复触发工作流。
     */
    @Value("${telegram.skip-old-updates-on-start:true}")
    private boolean skipOldUpdatesOnStart;

    /**
     * 应用的公开访问基础 URL。
     * <p>用于生成工作流详情页面的公开链接（如 https://example.invalid
     * 如果未设置，会尝试使用 wechat.public-app-base-url 作为备选。
     */
    @Value("${telegram.public-app-base-url:}")
    private String publicAppBaseUrl;

    /**
     * 默认研究组 ID（知识库 ID）。
     * <p>当用户或聊天未绑定特定研究组时，使用此默认值决定 RAG 检索的知识库。
     */
    @Value("${telegram.research-group-id:replace-me}")
    private String researchGroupId;

    /**
     * HTTP 请求超时时间（秒）。
     * <p>调用 Telegram API 时的请求超时，会被限制在 3-120 秒范围内。
     */
    @Value("${telegram.request-timeout-seconds:30}")
    private int requestTimeoutSeconds;

    /** 是否启用网络代理 */
    @Value("${telegram.proxy.enabled:false}")
    private boolean proxyEnabled;

    /** 代理类型：http（默认）、socks4、socks5 */
    @Value("${telegram.proxy.type:http}")
    private String proxyType;

    /** 代理服务器主机地址 */
    @Value("${telegram.proxy.host:}")
    private String proxyHost;

    /** 代理服务器端口号 */
    @Value("${telegram.proxy.port:0}")
    private int proxyPort;

    /**
     * 备选的公开应用基础 URL。
     * <p>当 telegram.public-app-base-url 未设置时，尝试使用微信配置中的 URL 作为备选。
     * 这是因为在同一个系统中，Telegram 和微信通常使用相同的前端地址。
     */
    @Value("${wechat.public-app-base-url:http://localhost:8080}")
    private String fallbackPublicAppBaseUrl;

    /** 获取 Bot Token */
    public String botToken() {
        return botToken;
    }

    /** 是否启用 Long Polling */
    public boolean pollingEnabled() {
        return pollingEnabled;
    }

    /**
     * 获取轮询超时时间（限制在 1-50 秒范围内）。
     * <p>Telegram API 限制 getUpdates 的 timeout 最大为 50 秒。
     */
    public int pollingTimeoutSeconds() {
        return Math.max(1, Math.min(pollingTimeoutSeconds, 50));
    }

    /**
     * 获取单次轮询的最大消息数（限制在 1-100 范围内）。
     * <p>Telegram API 限制 getUpdates 的 limit 最大为 100。
     */
    public int pollingLimit() {
        return Math.max(1, Math.min(pollingLimit, 100));
    }

    /** 是否在启动时跳过旧消息 */
    public boolean skipOldUpdatesOnStart() {
        return skipOldUpdatesOnStart;
    }

    /**
     * 获取 HTTP 请求超时时间（限制在 3-120 秒范围内）。
     * <p>低于 3 秒可能导致请求超时，高于 120 秒可能导致线程长时间阻塞。
     */
    public int requestTimeoutSeconds() {
        return Math.max(3, Math.min(requestTimeoutSeconds, 120));
    }

    /** 是否启用代理 */
    public boolean proxyEnabled() {
        return proxyEnabled;
    }

    /**
     * 获取代理类型（默认为 http）。
     *
     * @return 代理类型字符串："http"、"socks4" 或 "socks5"
     */
    public String proxyType() {
        return isBlank(proxyType) ? "http" : proxyType.trim().toLowerCase();
    }

    /** 获取代理主机地址 */
    public String proxyHost() {
        return proxyHost;
    }

    /** 获取代理端口号 */
    public int proxyPort() {
        return proxyPort;
    }

    /**
     * 检查代理配置是否完整。
     * <p>如果代理未启用则直接返回 true；如果启用了代理，
     * 则要求 host 非空且 port 大于 0。
     *
     * @return 代理配置是否有效
     */
    public boolean hasProxyConfig() {
        return !proxyEnabled || (!isBlank(proxyHost) && proxyPort > 0);
    }

    /**
     * 获取应用的公开访问基础 URL。
     * <p>优先使用 telegram.public-app-base-url，如果未设置则使用
     * wechat.public-app-base-url 作为备选，最终默认值为 http://localhost:8080。
     * 返回值会自动去除尾部的 "/"。
     *
     * @return 公开访问基础 URL
     */
    public String publicAppBaseUrl() {
        String value = isBlank(publicAppBaseUrl) ? fallbackPublicAppBaseUrl : publicAppBaseUrl;
        return isBlank(value) ? "http://localhost:8080" : trimRight(value, "/");
    }

    /**
     * 获取默认研究组 ID。
     * <p>如果配置为空则返回默认值 "replace-me"。
     *
     * @return 研究组 ID
     */
    public String researchGroupId() {
        return isBlank(researchGroupId) ? "replace-me" : researchGroupId.trim();
    }

    /**
     * 检查 Bot Token 是否已配置且格式正确。
     * <p>Bot Token 格式应为 "数字ID:随机字符串"，包含冒号分隔符。
     *
     * @return Bot Token 是否有效
     */
    public boolean hasBotToken() {
        return !isBlank(botToken) && botToken.contains(":");
    }

    /**
     * 检查公开应用基础 URL 是否配置为有效的公网地址。
     * <p>排除 localhost、127.0.0.1 和占位符域名。
     *
     * @return 是否配置了有效的公开 URL
     */
    public boolean hasPublicAppBaseUrl() {
        String value = publicAppBaseUrl();
        return !isBlank(value)
                && (value.startsWith("https://") || value.startsWith("http://"))
                && !value.contains("localhost")
                && !value.contains("127.0.0.1")
                && !value.contains("your-public-domain");
    }

    /**
     * 获取 Bot Token 配置错误信息。
     *
     * @return 如果配置正确返回空字符串，否则返回错误提示
     */
    public String botConfigError() {
        return hasBotToken() ? "" : "telegram.bot-token is required. Set TELEGRAM_BOT_TOKEN from BotFather.";
    }

    /**
     * 获取公开基础 URL 配置错误信息。
     *
     * @return 如果配置正确返回空字符串，否则返回错误提示
     */
    public String publicBaseUrlConfigError() {
        return hasPublicAppBaseUrl()
                ? ""
                : "telegram.public-app-base-url or PUBLIC_APP_BASE_URL should be a public URL for Workbench links";
    }

    /**
     * 获取代理配置错误信息。
     *
     * @return 如果配置正确返回空字符串，否则返回错误提示
     */
    public String proxyConfigError() {
        return hasProxyConfig()
                ? ""
                : "telegram.proxy.host and telegram.proxy.port are required when telegram.proxy.enabled=true";
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
