package com.team.rag.controller;

import com.team.rag.telegram.*;
import com.team.rag.queue.TelegramNotificationQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram 集成控制器 —— 管理 Telegram Bot 的配置、绑定和消息投递。
 *
 * <p>本控制器是 Telegram Bot 集成的管理入口，提供以下功能：</p>
 * <ul>
 *   <li><b>状态监控</b>：查看 Bot Token 配置、长轮询状态、代理配置等</li>
 *   <li><b>课题组绑定管理</b>：管理 Telegram 用户/群组与 RAG 课题组的绑定关系</li>
 *   <li><b>消息投递记录</b>：查看 Agent 回复的投递历史和状态</li>
 *   <li><b>测试发送</b>：向指定 Chat 发送测试消息</li>
 * </ul>
 *
 * <h3>REST 端点列表</h3>
 * <table border="1">
 *   <tr><th>HTTP 方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/telegram/status</td><td>Bot 状态和配置概览</td></tr>
 *   <tr><td>GET</td><td>/api/telegram/bindings</td><td>列出所有课题组绑定</td></tr>
 *   <tr><td>GET</td><td>/api/telegram/bindings/user/{id}</td><td>按用户 ID 查询绑定</td></tr>
 *   <tr><td>GET</td><td>/api/telegram/bindings/chat/{id}</td><td>按群组 ID 查询绑定</td></tr>
 *   <tr><td>GET</td><td>/api/telegram/bindings/resolve</td><td>解析消息的绑定关系</td></tr>
 *   <tr><td>POST</td><td>/api/telegram/bindings/user</td><td>绑定用户到课题组</td></tr>
 *   <tr><td>POST</td><td>/api/telegram/bindings/chat</td><td>绑定群组到课题组</td></tr>
 *   <tr><td>POST</td><td>/api/telegram/bindings/user/allow</td><td>为用户添加课题组访问权限</td></tr>
 *   <tr><td>POST</td><td>/api/telegram/bindings/chat/allow</td><td>为群组添加课题组访问权限</td></tr>
 *   <tr><td>GET</td><td>/api/telegram/deliveries</td><td>查看最近的投递记录</td></tr>
 *   <tr><td>GET</td><td>/api/telegram/deliveries/{id}</td><td>按 workflowId 查询投递记录</td></tr>
 *   <tr><td>POST</td><td>/api/telegram/test-send</td><td>发送测试消息</td></tr>
 * </table>
 *
 * @see TelegramProperties Telegram Bot 配置属性
 * @see TelegramUpdatePoller Telegram 消息长轮询器
 * @see TelegramBotClient Telegram Bot API 客户端
 * @see TelegramResearchGroupBindingStore 课题组绑定存储
 */
@RestController
@RequestMapping("/api/telegram")
public class TelegramController {

    /** Telegram Bot 配置属性（Token、代理、轮询等配置） */
    private final TelegramProperties properties;

    /** Telegram 消息长轮询器，负责接收 Bot 消息更新 */
    private final TelegramUpdatePoller poller;

    /** 消息投递存储，记录 Agent 回复的投递历史 */
    private final TelegramDeliveryStore deliveryStore;

    /** Telegram Bot API 客户端，用于发送消息 */
    private final TelegramBotClient botClient;

    /** 课题组绑定存储，管理 Telegram 用户/群组与课题组的映射关系 */
    private final TelegramResearchGroupBindingStore bindingStore;

    /** Telegram 通知队列，用于异步发送 Agent 回复 */
    private final TelegramNotificationQueue notificationQueue;

    /**
     * 构造函数，通过 Spring 依赖注入初始化各组件。
     *
     * @param properties       Telegram Bot 配置属性
     * @param poller           消息长轮询器
     * @param deliveryStore    投递记录存储
     * @param botClient        Bot API 客户端
     * @param bindingStore     课题组绑定存储
     * @param notificationQueue 通知队列
     */
    public TelegramController(
            TelegramProperties properties,
            TelegramUpdatePoller poller,
            TelegramDeliveryStore deliveryStore,
            TelegramBotClient botClient,
            TelegramResearchGroupBindingStore bindingStore,
            TelegramNotificationQueue notificationQueue) {
        this.properties = properties;
        this.poller = poller;
        this.deliveryStore = deliveryStore;
        this.botClient = botClient;
        this.bindingStore = bindingStore;
        this.notificationQueue = notificationQueue;
    }

    /**
     * Bot 状态概览端点 —— 返回 Telegram 集成的完整配置和运行状态。
     *
     * <p>包括 Bot Token 配置状态、长轮询运行状态、代理配置、
     * 课题组绑定统计、通知队列状态、最近投递记录等。</p>
     *
     * @return 包含所有配置和状态信息的 Map
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Bot 基本配置状态
        result.put("botConfigured", properties.hasBotToken());
        result.put("botConfigError", properties.botConfigError());

        // 长轮询配置与运行状态
        result.put("pollingEnabled", properties.pollingEnabled());
        result.put("pollingRunning", poller.isRunning());
        result.put("pollingTimeoutSeconds", properties.pollingTimeoutSeconds());
        result.put("pollingLimit", properties.pollingLimit());
        result.put("skipOldUpdatesOnStart", properties.skipOldUpdatesOnStart());
        result.put("requestTimeoutSeconds", properties.requestTimeoutSeconds());

        // 代理配置状态
        result.put("proxyEnabled", properties.proxyEnabled());
        result.put("proxyConfigured", properties.hasProxyConfig());
        result.put("proxyConfigError", properties.proxyConfigError());
        result.put("proxyType", properties.proxyType());
        result.put("proxyHost", properties.proxyEnabled() ? properties.proxyHost() : "");
        result.put("proxyPort", properties.proxyEnabled() ? properties.proxyPort() : 0);

        // 轮询运行指标
        result.put("botUsername", poller.getBotUsername());
        result.put("lastPollAt", poller.getLastPollAt());
        result.put("lastError", poller.getLastError());
        result.put("nextOffset", poller.getNextOffset());
        result.put("processedUpdates", poller.getProcessedUpdates());
        result.put("ignoredUpdates", poller.getIgnoredUpdates());

        // 公网访问配置
        result.put("publicBaseUrlConfigured", properties.hasPublicAppBaseUrl());
        result.put("publicBaseUrlConfigError", properties.publicBaseUrlConfigError());
        result.put("publicAppBaseUrl", properties.publicAppBaseUrl());
        result.put("researchGroupId", properties.researchGroupId());

        // 课题组绑定状态
        result.put("bindingStorageMode", bindingStore.storageMode());
        result.put("bindingRedisAvailable", bindingStore.isRedisAvailable());
        result.put("bindingCount", bindingStore.listBindings().size());

        // Agent 控制台和投递模式
        result.put("agentConsoleUrl", properties.publicAppBaseUrl() + "/app/");
        result.put("deliveryMode", "long_polling_then_sendMessage");

        // 通知队列状态
        result.put("queue", notificationQueue.status());
        result.put("recentDeliveries", deliveryStore.recentDeliveries(5));

        // 环境变量参考（方便运维排查配置问题）
        result.put("requiredEnvironment", Map.ofEntries(
                Map.entry("TELEGRAM_BOT_TOKEN", "BotFather token"),
                Map.entry("TELEGRAM_POLLING_ENABLED", "true to enable local long polling"),
                Map.entry("TELEGRAM_PROXY_ENABLED", "true when local network requires a proxy to access api.telegram.org"),
                Map.entry("TELEGRAM_PROXY_TYPE", "http, socks4, or socks5"),
                Map.entry("TELEGRAM_PROXY_HOST", "proxy host, for example 127.0.0.1"),
                Map.entry("TELEGRAM_PROXY_PORT", "proxy port, for example 7890"),
                Map.entry("TELEGRAM_RESEARCH_GROUP_ID", "literature library researchGroupId, default replace-me"),
                Map.entry("PUBLIC_APP_BASE_URL", "public Workbench base URL used in Telegram replies"),
                Map.entry("AGENT_QUEUE_BACKEND", "local or kafka"),
                Map.entry("AGENT_QUEUE_KAFKA_ENABLED", "true to consume Telegram notification tasks from Kafka"),
                Map.entry("KAFKA_BOOTSTRAP_SERVERS", "Kafka bootstrap servers, for example localhost:9092")
        ));
        return ResponseEntity.ok(result);
    }

    /**
     * 列出所有课题组绑定 —— 返回当前所有 Telegram 用户/群组与课题组的绑定关系。
     *
     * @return 绑定列表
     */
    @GetMapping("/bindings")
    public ResponseEntity<List<TelegramResearchGroupBinding>> bindings() {
        return ResponseEntity.ok(bindingStore.listBindings());
    }

    /**
     * 按 Telegram 用户 ID 查询绑定 —— 查询单个用户的课题组绑定。
     *
     * @param telegramUserId Telegram 用户的数字 ID
     * @return 绑定信息，未找到时返回 404
     */
    @GetMapping("/bindings/user/{telegramUserId}")
    public ResponseEntity<TelegramResearchGroupBinding> userBinding(@PathVariable long telegramUserId) {
        TelegramResearchGroupBinding binding = bindingStore.getUserBinding(telegramUserId);
        if (binding == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(binding);
    }

    /**
     * 按 Telegram 群组 ID 查询绑定 —— 查询单个群组的课题组绑定。
     *
     * @param telegramChatId Telegram 群组/频道的数字 ID
     * @return 绑定信息，未找到时返回 404
     */
    @GetMapping("/bindings/chat/{telegramChatId}")
    public ResponseEntity<TelegramResearchGroupBinding> chatBinding(@PathVariable long telegramChatId) {
        TelegramResearchGroupBinding binding = bindingStore.getChatBinding(telegramChatId);
        if (binding == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(binding);
    }

    /**
     * 解析消息绑定关系 —— 模拟一条入站消息，查看其最终解析到的课题组。
     *
     * <p>用于调试绑定优先级和解析逻辑。根据用户 ID、群组 ID 和聊天类型
     * 综合判断应使用哪个课题组。</p>
     *
     * @param telegramUserId Telegram 用户 ID
     * @param telegramChatId Telegram 群组 ID
     * @param chatType       聊天类型（默认 "private"，可选 "group" 等）
     * @return 解析结果，包含最终确定的课题组信息
     */
    @GetMapping("/bindings/resolve")
    public ResponseEntity<TelegramResearchGroupResolution> resolveBinding(
            @RequestParam("telegramUserId") long telegramUserId,
            @RequestParam("telegramChatId") long telegramChatId,
            @RequestParam(value = "chatType", defaultValue = "private") String chatType) {
        // 构造模拟的入站消息对象
        TelegramInboundMessage message = new TelegramInboundMessage();
        message.fromId = telegramUserId;
        message.chatId = telegramChatId;
        message.chatType = chatType;
        return ResponseEntity.ok(bindingStore.resolve(message));
    }

    /**
     * 绑定用户到课题组 —— 将 Telegram 用户绑定到指定的 RAG 课题组。
     *
     * @param request 绑定请求体，包含 telegramUserId、researchGroupId 和 updatedBy
     * @return 绑定结果或错误信息
     */
    @PostMapping("/bindings/user")
    public ResponseEntity<?> bindUser(@RequestBody BindingRequest request) {
        if (request == null || request.telegramUserId == null) {
            return badRequest("telegramUserId is required");
        }
        return bind(() -> bindingStore.bindUser(
                request.telegramUserId,
                request.researchGroupId,
                updatedBy(request)));
    }

    /**
     * 绑定群组到课题组 —— 将 Telegram 群组绑定到指定的 RAG 课题组。
     *
     * @param request 绑定请求体，包含 telegramChatId、researchGroupId 和 updatedBy
     * @return 绑定结果或错误信息
     */
    @PostMapping("/bindings/chat")
    public ResponseEntity<?> bindChat(@RequestBody BindingRequest request) {
        if (request == null || request.telegramChatId == null) {
            return badRequest("telegramChatId is required");
        }
        return bind(() -> bindingStore.bindChat(
                request.telegramChatId,
                request.researchGroupId,
                updatedBy(request)));
    }

    /**
     * 为用户添加课题组访问权限 —— 允许用户访问指定课题组（不改变主绑定）。
     *
     * @param request 权限请求体
     * @return 操作结果或错误信息
     */
    @PostMapping("/bindings/user/allow")
    public ResponseEntity<?> allowUser(@RequestBody BindingRequest request) {
        if (request == null || request.telegramUserId == null) {
            return badRequest("telegramUserId is required");
        }
        return bind(() -> bindingStore.allowForUser(
                request.telegramUserId,
                request.researchGroupId,
                updatedBy(request)));
    }

    /**
     * 为群组添加课题组访问权限 —— 允许群组访问指定课题组（不改变主绑定）。
     *
     * @param request 权限请求体
     * @return 操作结果或错误信息
     */
    @PostMapping("/bindings/chat/allow")
    public ResponseEntity<?> allowChat(@RequestBody BindingRequest request) {
        if (request == null || request.telegramChatId == null) {
            return badRequest("telegramChatId is required");
        }
        return bind(() -> bindingStore.allowForChat(
                request.telegramChatId,
                request.researchGroupId,
                updatedBy(request)));
    }

    /**
     * 查看最近投递记录 —— 获取 Agent 回复向 Telegram 投递的历史记录。
     *
     * @param limit 最大返回数量（默认 30）
     * @return 投递记录列表
     */
    @GetMapping("/deliveries")
    public ResponseEntity<List<TelegramDeliveryRecord>> deliveries(
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return ResponseEntity.ok(deliveryStore.recentDeliveries(limit));
    }

    /**
     * 按 workflowId 查询投递记录 —— 查看单次工作流的投递详情。
     *
     * @param workflowId 工作流 ID
     * @return 投递记录，未找到时返回 404
     */
    @GetMapping("/deliveries/{workflowId}")
    public ResponseEntity<TelegramDeliveryRecord> delivery(@PathVariable String workflowId) {
        TelegramDeliveryRecord record = deliveryStore.getDelivery(workflowId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /**
     * 发送测试消息端点 —— 向指定的 Telegram Chat 发送一条纯文本测试消息。
     *
     * <p>用于验证 Bot Token 和 Chat 配置是否正确。</p>
     *
     * @param chatId 目标 Chat ID
     * @param text   消息文本（默认 "Telegram Agent test message"）
     * @return 发送结果
     */
    @PostMapping("/test-send")
    public ResponseEntity<TelegramSendResult> testSend(
            @RequestParam("chatId") long chatId,
            @RequestParam(value = "text", defaultValue = "Telegram Agent test message") String text) {
        return ResponseEntity.ok(botClient.sendPlainText(chatId, text));
    }

    // ==================== 内部工具方法 ====================

    /**
     * 执行绑定操作的通用包装器，捕获参数校验异常并返回 400 响应。
     *
     * @param operation 绑定操作（函数式接口）
     * @return 操作结果或 400 错误响应
     */
    private ResponseEntity<?> bind(BindingOperation operation) {
        try {
            return ResponseEntity.ok(operation.apply());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /**
     * 构建 400 Bad Request 响应。
     *
     * @param message 错误信息
     * @return 包含 error 字段的 400 响应
     */
    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /**
     * 从绑定请求中提取操作者标识。
     *
     * @param request 绑定请求体
     * @return 操作者名称，默认为 "api"
     */
    private String updatedBy(BindingRequest request) {
        if (request != null && request.updatedBy != null && !request.updatedBy.isBlank()) {
            return request.updatedBy.trim();
        }
        return "api";
    }

    // ==================== 内部接口和 DTO 类 ====================

    /**
     * 绑定操作函数式接口 —— 用于包装各种绑定操作以统一异常处理。
     */
    @FunctionalInterface
    private interface BindingOperation {
        /**
         * 执行绑定操作。
         *
         * @return 绑定结果
         * @throws IllegalArgumentException 参数校验失败时抛出
         */
        TelegramResearchGroupBinding apply();
    }

    /**
     * 绑定请求体 DTO —— 用于用户/群组绑定和权限添加的请求参数。
     */
    public static class BindingRequest {
        /** Telegram 用户 ID（绑定用户时必需） */
        public Long telegramUserId;
        /** Telegram 群组 ID（绑定群组时必需） */
        public Long telegramChatId;
        /** 目标课题组 ID */
        public String researchGroupId;
        /** 操作者标识（用于审计日志，默认 "api"） */
        public String updatedBy;
    }
}
