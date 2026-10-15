package com.team.rag.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot HTTP 客户端。
 *
 * <p>封装了 Telegram Bot API 的核心调用逻辑，负责与 Telegram 服务器进行通信。
 * 在本学术 RAG 系统中，该组件是 Telegram 消息通道的底层网络层，承担以下职责：
 * <ul>
 *   <li>发送消息：将工作流处理结果分片发送到指定聊天（支持超长文本自动拆分）；</li>
 *   <li>拉取消息：通过 Long Polling 方式获取用户发送的更新（getUpdates）；</li>
 *   <li>Webhook 管理：删除已有 Webhook 以确保轮询模式正常工作；</li>
 *   <li>代理支持：根据配置通过 HTTP/SOCKS4/SOCKS5 代理访问 Telegram API。</li>
 * </ul>
 *
 * <p>该类通过 {@link WebClient}（基于 Reactor Netty）发起异步 HTTP 请求，并在
 * {@link TelegramUpdatePoller} 和 {@link TelegramWorkflowNotifier} 中被调用。
 *
 * @see TelegramProperties 配置属性
 * @see TelegramUpdatePoller 消息轮询器
 * @see TelegramWorkflowNotifier 工作流结果通知器
 */
@Service
public class TelegramBotClient {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);

    /**
     * Telegram 消息最大字符数限制。
     * <p>Telegram 单条消息文本上限为 4096 字符，此处预留 96 字符余量用于安全裕度，
     * 防止因特殊字符编码等原因导致消息被截断。
     */
    private static final int MAX_TELEGRAM_TEXT_LENGTH = 3900;

    /** Telegram 配置属性（Bot Token、代理等） */
    private final TelegramProperties properties;

    /** Jackson JSON 对象映射器，用于解析 Telegram API 返回的 JSON 响应 */
    private final ObjectMapper objectMapper;

    /** 响应式 HTTP 客户端，用于向 Telegram API 发起 GET/POST 请求 */
    private final WebClient webClient;

    /**
     * 缓存的 Bot 用户名（不含 @ 前缀）。
     * <p>通过 getMe API 获取后缓存，用于判断群聊消息是否 @ 了本 Bot。
     * 使用 volatile 保证多线程可见性。
     */
    private volatile String botUsername;

    /**
     * 构造函数，初始化 HTTP 客户端并配置代理。
     *
     * @param properties   Telegram 配置属性，包含 Bot Token、超时时间、代理设置等
     * @param objectMapper Jackson JSON 映射器（由 Spring 自动注入）
     * @param builder      WebClient 构建器（由 Spring 自动注入），用于创建 HTTP 客户端
     */
    public TelegramBotClient(TelegramProperties properties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 创建 Reactor Netty HTTP 客户端并设置响应超时
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds()));
        // 如果启用了代理且代理配置有效，则配置代理
        if (properties.proxyEnabled() && properties.hasProxyConfig()) {
            httpClient = httpClient.proxy(proxy -> proxy
                    .type(proxyType())
                    .host(properties.proxyHost())
                    .port(properties.proxyPort()));
            log.info("Telegram proxy enabled | type={} | host={} | port={}",
                    properties.proxyType(), properties.proxyHost(), properties.proxyPort());
        }
        // 使用自定义 HttpClient 构建 WebClient
        this.webClient = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 调用 Telegram getMe 接口获取当前 Bot 的基本信息。
     *
     * <p>该方法会同时缓存 Bot 的用户名（username），供后续判断群聊 @ 消息使用。
     *
     * @return Telegram API 返回的 result 节点，包含 Bot 的 id、username、first_name 等信息
     * @throws IllegalStateException 如果 API 调用失败或返回非 ok 状态
     */
    public JsonNode getMe() {
        JsonNode response = parseJson(get(apiUrl("getMe")));
        ensureOk(response, "getMe");
        JsonNode result = response.path("result");
        // 缓存 Bot 用户名，用于后续群聊中 @mention 检测
        if (result.hasNonNull("username")) {
            botUsername = result.get("username").asText();
        }
        return result;
    }

    /**
     * 获取缓存的 Bot 用户名。
     *
     * @return Bot 用户名（不含 @ 前缀），如果尚未通过 getMe 获取则返回 null
     */
    public String botUsername() {
        return botUsername;
    }

    /**
     * 删除 Telegram Webhook。
     *
     * <p>在使用 Long Polling 模式之前，必须先删除已有的 Webhook 配置，
     * 否则 getUpdates 接口将无法正常工作。
     *
     * @param dropPendingUpdates 是否丢弃 Webhook 模式下积压的未处理消息
     */
    public void deleteWebhook(boolean dropPendingUpdates) {
        JsonNode response = parseJson(post(apiUrl("deleteWebhook"),
                Map.of("drop_pending_updates", dropPendingUpdates)));
        ensureOk(response, "deleteWebhook");
    }

    /**
     * 通过 Long Polling 方式拉取 Telegram 更新消息。
     *
     * <p>使用 Telegram 的 getUpdates 接口，支持长轮询（long polling）模式：
     * 服务端会在有新消息时立即返回，或者在超时后返回空列表。
     *
     * @param offset        起始更新 ID（offset），传 null 从最新消息开始拉取
     * @param timeoutSeconds 长轮询超时时间（秒），在此时间内无新消息则返回空
     * @param limit         单次拉取的最大更新数量，范围 1-100
     * @return 包含更新消息的 JSON 数组节点
     * @throws IllegalStateException 如果 API 调用失败
     */
    public JsonNode getUpdates(Long offset, int timeoutSeconds, int limit) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl("getUpdates"))
                .queryParam("timeout", timeoutSeconds)
                .queryParam("limit", limit);
        if (offset != null) {
            builder.queryParam("offset", offset);
        }
        JsonNode response = parseJson(get(builder.toUriString()));
        ensureOk(response, "getUpdates");
        return response.path("result");
    }

    /**
     * 将工作流运行结果发送到指定 Telegram 聊天。
     *
     * <p>该方法会构建包含最终答案和工作流链接的消息文本，然后调用 sendPlainText 发送。
     * 如果工作流失败，会发送错误信息；如果工作流成功但无最终答案，则发送默认提示。
     *
     * @param chatId   目标聊天 ID（可以是用户私聊 ID 或群组 ID）
     * @param snapshot 工作流运行快照，包含状态、最终答案、错误信息等
     * @return 发送结果，包含成功/失败状态和 Telegram API 原始响应
     */
    public TelegramSendResult sendWorkflowResult(long chatId, WorkflowRunSnapshot snapshot) {
        if (!properties.hasBotToken()) {
            return TelegramSendResult.failure("CONFIG", properties.botConfigError());
        }
        // 构建工作流详情页面的公开链接
        String link = publicWorkflowLink(snapshot.workflowId);
        // 组装最终消息文本（答案 + 链接）
        String content = buildFinalMessage(snapshot, link);
        return sendPlainText(chatId, content);
    }

    /**
     * 向指定 Telegram 聊天发送纯文本消息。
     *
     * <p>如果消息内容超过 {@link #MAX_TELEGRAM_TEXT_LENGTH} 字符，会自动拆分为多条消息
     * 依次发送。任何一条发送失败都会立即中止并返回失败结果。
     *
     * @param chatId  目标聊天 ID
     * @param content 要发送的文本内容
     * @return 发送结果，包含成功状态、分片总数、已发送分片数、API 响应等
     */
    public TelegramSendResult sendPlainText(long chatId, String content) {
        if (!properties.hasBotToken()) {
            return TelegramSendResult.failure("CONFIG", properties.botConfigError());
        }

        TelegramSendResult result = new TelegramSendResult();
        // 将长文本拆分为多个分片
        List<String> chunks = split(content, MAX_TELEGRAM_TEXT_LENGTH);
        result.chunksTotal = chunks.size();
        for (String chunk : chunks) {
            // 逐片发送，每发送一片都检查 API 响应
            String response = sendText(chatId, chunk);
            result.apiResponses.add(response);
            JsonNode json = parseJson(response);
            // 检查 Telegram API 返回的 ok 字段
            if (!json.path("ok").asBoolean(false)) {
                result.success = false;
                result.errorCode = json.has("error_code") ? json.get("error_code").asText() : "UNKNOWN";
                result.errorMessage = json.has("description") ? json.get("description").asText() : response;
                log.warn("Telegram message rejected | chatId={} | errorCode={} | error={}",
                        chatId, result.errorCode, result.errorMessage);
                return result; // 发送失败，立即返回
            }
            result.chunksSent++;
        }
        result.success = true;
        return result;
    }

    /**
     * 生成工作流详情页面的公开访问链接。
     *
     * @param workflowId 工作流 ID
     * @return 完整的前端页面 URL，格式为 {publicAppBaseUrl}/app/#/workflow/{workflowId}
     */
    public String publicWorkflowLink(String workflowId) {
        return properties.publicAppBaseUrl() + "/app/#/workflow/" + workflowId;
    }

    /**
     * 构建发送给用户的最终消息文本。
     *
     * <p>根据工作流状态组装消息内容：
     * <ul>
     *   <li>如果工作流失败，显示错误信息；</li>
     *   <li>如果工作流成功但无最终答案，显示默认提示；</li>
     *   <li>否则显示最终答案内容。</li>
     * </ul>
     * 消息末尾附加工作流详情页面链接。
     *
     * @param snapshot 工作流运行快照
     * @param link     工作流详情页面链接
     * @return 组装好的消息文本
     */
    private String buildFinalMessage(WorkflowRunSnapshot snapshot, String link) {
        String answer;
        if ("FAILED".equalsIgnoreCase(snapshot.status)) {
            // 工作流执行失败，发送错误信息
            answer = "Agent run failed: " + defaultString(snapshot.errorMessage, "unknown error");
        } else {
            // 工作流完成，使用最终答案或默认提示
            answer = defaultString(snapshot.finalAnswer,
                    "The workflow finished but did not return a final answer.");
        }
        return answer + "\n" + link;
    }

    /**
     * 调用 Telegram sendMessage API 发送单条文本消息。
     *
     * @param chatId  目标聊天 ID
     * @param content 消息文本内容
     * @return Telegram API 返回的原始 JSON 字符串
     */
    private String sendText(long chatId, String content) {
        return post(apiUrl("sendMessage"), Map.of(
                "chat_id", chatId,
                "text", content
        ));
    }

    /**
     * 发送 HTTP GET 请求到指定 URL。
     *
     * @param url 请求的完整 URL
     * @return 响应体字符串
     * @throws IllegalStateException 如果请求失败
     */
    private String get(String url) {
        try {
            return webClient.get()
                    .uri(url)
                    .exchangeToMono(response -> response.bodyToMono(String.class))
                    .blockOptional()
                    .orElse("");
        } catch (Exception e) {
            throw new IllegalStateException("Telegram GET request failed: " + sanitize(e.getMessage()));
        }
    }

    /**
     * 发送 HTTP POST 请求到指定 URL，携带 JSON 请求体。
     *
     * @param url  请求的完整 URL
     * @param body 请求体键值对，将被序列化为 JSON
     * @return 响应体字符串
     * @throws IllegalStateException 如果请求失败
     */
    private String post(String url, Map<String, Object> body) {
        try {
            return webClient.post()
                    .uri(url)
                    .bodyValue(body)
                    .exchangeToMono(response -> response.bodyToMono(String.class))
                    .blockOptional()
                    .orElse("");
        } catch (Exception e) {
            throw new IllegalStateException("Telegram POST request failed: " + sanitize(e.getMessage()));
        }
    }

    /**
     * 拼接 Telegram Bot API 的完整请求 URL。
     *
     * <p>格式为：https://api.telegram.org/bot{token}/{method}
     *
     * @param method API 方法名（如 getMe、sendMessage、getUpdates 等）
     * @return 完整的 API 请求 URL
     */
    private String apiUrl(String method) {
        return "https://api.telegram.org/bot" + properties.botToken() + "/" + method;
    }

    /**
     * 将配置文件中的代理类型字符串转换为 Reactor Netty 的 Proxy 枚举。
     *
     * @return 代理类型枚举值，支持 HTTP（默认）、SOCKS4、SOCKS5
     */
    private ProxyProvider.Proxy proxyType() {
        return switch (properties.proxyType()) {
            case "socks4" -> ProxyProvider.Proxy.SOCKS4;
            case "socks5" -> ProxyProvider.Proxy.SOCKS5;
            default -> ProxyProvider.Proxy.HTTP;
        };
    }

    /**
     * 校验 Telegram API 响应状态。
     *
     * <p>检查 JSON 响应中的 "ok" 字段，如果不是 true 则抛出异常。
     *
     * @param response Telegram API 返回的 JSON 响应
     * @param method   调用的 API 方法名，用于异常消息
     * @throws IllegalStateException 如果响应状态不是 ok
     */
    private void ensureOk(JsonNode response, String method) {
        if (!response.path("ok").asBoolean(false)) {
            String code = response.has("error_code") ? response.get("error_code").asText() : "UNKNOWN";
            String description = response.has("description") ? response.get("description").asText() : response.toString();
            throw new IllegalStateException("Telegram " + method + " failed: error_code=" + code
                    + ", description=" + description);
        }
    }

    /**
     * 将 JSON 字符串解析为 Jackson JsonNode 对象。
     *
     * @param response JSON 字符串，null 或空白字符串会被当作空 JSON 对象 "{}" 处理
     * @return 解析后的 JsonNode
     * @throws IllegalStateException 如果 JSON 解析失败
     */
    private JsonNode parseJson(String response) {
        try {
            return objectMapper.readTree(response == null || response.isBlank() ? "{}" : response);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse Telegram API response: " + response, e);
        }
    }

    /**
     * 将长文本按指定最大长度拆分为多个分片。
     *
     * <p>用于将超过 Telegram 单条消息限制的文本拆分为多条消息依次发送。
     * 拆分时不考虑语义边界，仅按字符数截断。
     *
     * @param content   原始文本内容
     * @param maxLength 每个分片的最大字符数
     * @return 分片列表，按原始顺序排列
     */
    private List<String> split(String content, int maxLength) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + maxLength);
            chunks.add(content.substring(start, end));
            start = end;
        }
        return chunks;
    }

    /**
     * 返回非空字符串，如果 value 为 null 或空白则返回 fallback。
     *
     * @param value    待检查的字符串
     * @param fallback 替代值
     * @return 有效的字符串值
     */
    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 对错误消息进行脱敏处理，将 Bot Token 替换为 "***"。
     *
     * <p>防止在日志或异常消息中泄露敏感的 Bot Token。
     *
     * @param message 原始错误消息
     * @return 脱敏后的消息
     */
    private String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String token = properties.botToken();
        if (token == null || token.isBlank()) {
            return message;
        }
        // 将消息中出现的 Bot Token 替换为掩码
        return message.replace(token, "***");
    }
}
