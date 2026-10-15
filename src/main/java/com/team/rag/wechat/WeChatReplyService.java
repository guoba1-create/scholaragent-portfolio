package com.team.rag.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 微信回复服务。
 *
 * <p>负责通过微信客服消息接口向用户发送工作流处理结果。
 * 在本学术 RAG 系统中，该组件是微信消息通道的发送层，承担以下职责：
 * <ul>
 *   <li>管理 access_token：自动获取和缓存微信 API 的访问令牌；</li>
 *   <li>发送消息：调用微信客服消息接口发送文本回复；</li>
 *   <li>消息分片：超长文本自动拆分为多条消息发送；</li>
 *   <li>链接生成：构建工作流详情页面的公开链接。</li>
 * </ul>
 *
 * <p>access_token 缓存策略：获取后缓存，在过期前 120 秒自动刷新，
 * 避免频繁请求微信 API。
 *
 * @see WeChatProperties 微信配置属性
 * @see WeChatSendResult 发送结果
 * @see WeChatWorkflowNotifier 工作流通知器（调用本服务发送结果）
 */
@Service
public class WeChatReplyService {

    private static final Logger log = LoggerFactory.getLogger(WeChatReplyService.class);

    /**
     * 微信客服消息最大字符数限制。
     * <p>微信单条客服消息文本上限为 2048 字符，此处预留 248 字符余量，
     * 防止因特殊字符编码等原因导致消息被截断。
     */
    private static final int MAX_WECHAT_TEXT_LENGTH = 1800;

    /** 微信配置属性 */
    private final WeChatProperties properties;

    /** Jackson JSON 映射器 */
    private final ObjectMapper objectMapper;

    /** 响应式 HTTP 客户端 */
    private final WebClient webClient;

    /** 缓存的 access_token */
    private String cachedAccessToken;

    /** access_token 的过期时间（提前 120 秒刷新） */
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    /**
     * 构造函数，注入依赖。
     *
     * @param properties   微信配置属性
     * @param objectMapper JSON 映射器
     * @param builder      WebClient 构建器
     */
    public WeChatReplyService(WeChatProperties properties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.build();
    }

    /**
     * 将工作流运行结果发送给微信用户。
     *
     * <p>该方法会构建包含最终答案和工作流链接的消息文本，然后通过微信客服消息接口
     * 发送给指定用户。如果消息超长会自动拆分为多条发送。
     *
     * @param openId   微信用户的 OpenID
     * @param snapshot 工作流运行快照
     * @return 发送结果，包含成功/失败状态和微信 API 原始响应
     */
    public WeChatSendResult sendWorkflowResult(String openId, WorkflowRunSnapshot snapshot) {
        // 检查 AppID 和 AppSecret 是否已配置
        if (!properties.hasReplyConfig()) {
            log.warn(properties.replyConfigError());
            return WeChatSendResult.failure("CONFIG", properties.replyConfigError());
        }
        WeChatSendResult result = new WeChatSendResult();
        String link = publicWorkflowLink(snapshot.workflowId);
        // 组装最终消息文本（答案 + 链接）
        String content = buildFinalMessage(snapshot, link);
        // 将长文本拆分为多个分片
        List<String> chunks = split(content, MAX_WECHAT_TEXT_LENGTH);
        result.chunksTotal = chunks.size();
        for (String chunk : chunks) {
            // 逐片发送，每发送一片都检查 API 响应
            String response = sendText(openId, chunk);
            result.apiResponses.add(response);
            JsonNode json = parseJson(response);
            // 微信 API 错误码为 0 表示成功
            int errCode = json.path("errcode").asInt(0);
            if (errCode != 0) {
                result.success = false;
                result.errorCode = String.valueOf(errCode);
                result.errorMessage = json.path("errmsg").asText(response);
                log.warn("WeChat custom message rejected | openId={} | workflowId={} | errcode={} | errmsg={}",
                        mask(openId), snapshot.workflowId, result.errorCode, result.errorMessage);
                return result; // 发送失败，立即返回
            }
            result.chunksSent++;
        }
        result.success = true;
        log.info("WeChat workflow result sent | openId={} | workflowId={} | chunks={}",
                mask(openId), snapshot.workflowId, result.chunksSent);
        return result;
    }

    /**
     * 生成工作流详情页面的公开访问链接。
     *
     * @param workflowId 工作流 ID
     * @return 完整的前端页面 URL
     */
    public String publicWorkflowLink(String workflowId) {
        return properties.publicAppBaseUrl() + "/app/#/workflow/" + workflowId;
    }

    /**
     * 构建发送给用户的最终消息文本。
     *
     * @param snapshot 工作流运行快照
     * @param link     工作流详情页面链接
     * @return 组装好的消息文本
     */
    private String buildFinalMessage(WorkflowRunSnapshot snapshot, String link) {
        String answer;
        if ("FAILED".equalsIgnoreCase(snapshot.status)) {
            answer = "Agent run failed: " + defaultString(snapshot.errorMessage, "unknown error");
        } else {
            answer = defaultString(snapshot.finalAnswer,
                    "The workflow finished but did not return a final answer.");
        }
        return answer + "\n" + link;
    }

    /**
     * 调用微信客服消息接口发送单条文本消息。
     *
     * @param openId  微信用户的 OpenID
     * @param content 消息文本内容
     * @return 微信 API 返回的原始 JSON 字符串
     * @throws IllegalStateException 如果发送失败
     */
    private String sendText(String openId, String content) {
        try {
            String token = accessToken();
            Map<String, Object> body = Map.of(
                    "touser", openId,
                    "msgtype", "text",
                    "text", Map.of("content", content)
            );
            return webClient.post()
                    .uri("https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token={token}", token)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .blockOptional()
                    .orElse("");
        } catch (Exception e) {
            log.error("Failed to send WeChat custom message | openId={}, error={}", openId, e.getMessage(), e);
            throw new IllegalStateException("Failed to send WeChat custom message: " + e.getMessage(), e);
        }
    }

    /**
     * 获取微信 API 的 access_token。
     *
     * <p>使用 synchronized 保证线程安全，避免多线程同时刷新 token。
     * 缓存策略：在 token 过期前 120 秒自动刷新，避免使用即将过期的 token。
     *
     * @return 有效的 access_token
     * @throws IllegalStateException 如果获取失败
     */
    private synchronized String accessToken() throws Exception {
        // 检查缓存的 token 是否仍然有效
        if (cachedAccessToken != null && Instant.now().isBefore(accessTokenExpiresAt)) {
            return cachedAccessToken;
        }
        // 向微信 API 请求新的 access_token
        String response = webClient.get()
                .uri("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appId}&secret={secret}",
                        properties.appId(), properties.appSecret())
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode json = objectMapper.readTree(response);
        if (json.hasNonNull("access_token")) {
            cachedAccessToken = json.get("access_token").asText();
            int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt(7200) : 7200;
            // 提前 120 秒刷新，避免使用即将过期的 token；最少缓存 60 秒
            accessTokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 120));
            return cachedAccessToken;
        }
        // 获取失败，抛出异常
        String errCode = json.has("errcode") ? json.get("errcode").asText() : "UNKNOWN";
        String errMsg = json.has("errmsg") ? json.get("errmsg").asText() : response;
        throw new IllegalStateException("Unable to fetch WeChat access token: errcode=" + errCode + ", errmsg=" + errMsg);
    }

    /**
     * 将 JSON 字符串解析为 Jackson JsonNode 对象。
     */
    private JsonNode parseJson(String response) {
        try {
            return objectMapper.readTree(response == null || response.isBlank() ? "{}" : response);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse WeChat API response: " + response, e);
        }
    }

    /**
     * 将长文本按指定最大长度拆分为多个分片。
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
     */
    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 对字符串进行脱敏处理（前4位 + **** + 后4位）。
     * <p>用于日志输出中保护用户 OpenID 隐私。
     */
    private String mask(String value) {
        if (value == null || value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
