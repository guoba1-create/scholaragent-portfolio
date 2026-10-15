package com.team.rag.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.rag.agent.runtime.WorkflowRunRequest;
import com.team.rag.agent.runtime.WorkflowRunService;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import com.team.rag.queue.TelegramNotificationQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Telegram 更新轮询器。
 *
 * <p>通过 Long Polling 方式持续拉取 Telegram 消息并进行处理。
 * 在本学术 RAG 系统中，该组件是 Telegram 消息通道的核心入口，负责：
 * <ul>
 *   <li>接收用户消息：通过 getUpdates API 拉取用户发送的消息；</li>
 *   <li>命令解析：处理 /help、/ask、/status、/use、/allow 等命令；</li>
 *   <li>消息去重：基于 updateId 防止重复处理；</li>
 *   <li>工作流触发：将用户问题提交给 WorkflowRunService 进行 RAG 处理；</li>
 *   <li>异步通知：将工作流 ID 加入通知队列，等待结果完成后回复用户。</li>
 * </ul>
 *
 * <p>该组件在应用启动后（ApplicationReadyEvent）自动启动后台轮询线程，
 * 在应用关闭前（@PreDestroy）优雅停止。
 *
 * @see TelegramBotClient Bot API 客户端（负责网络通信）
 * @see TelegramDeliveryStore 投递记录存储
 * @see TelegramResearchGroupBindingStore 研究组绑定存储
 * @see TelegramNotificationQueue 通知队列
 */
@Component
public class TelegramUpdatePoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdatePoller.class);

    /** Telegram 配置属性 */
    private final TelegramProperties properties;

    /** Telegram Bot API 客户端 */
    private final TelegramBotClient botClient;

    /** 投递记录存储 */
    private final TelegramDeliveryStore deliveryStore;

    /** 研究组绑定存储 */
    private final TelegramResearchGroupBindingStore bindingStore;

    /** 工作流运行服务（触发 RAG 工作流） */
    private final WorkflowRunService workflowRunService;

    /** Telegram 通知队列（异步等待工作流完成后回复用户） */
    private final TelegramNotificationQueue notificationQueue;

    /**
     * 单线程执行器，运行后台轮询循环。
     * <p>使用守护线程，不会阻止 JVM 正常退出。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telegram-update-poller");
        thread.setDaemon(true);
        return thread;
    });

    /** 轮询是否正在运行的标志（使用 AtomicBoolean 保证线程安全） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 下一次拉取的起始 offset。
     * <p>Telegram API 会返回 update_id >= offset 的消息，拉取后自动递增。
     */
    private volatile Long nextOffset;

    /** 最后一次成功轮询的时间 */
    private volatile Instant lastPollAt;

    /** 最后一次轮询错误信息（无错误时为空字符串） */
    private volatile String lastError = "";

    /** 已处理的有效消息总数 */
    private volatile long processedUpdates;

    /** 已忽略的消息总数（包括非文本消息、@mention 不匹配、命令消息等） */
    private volatile long ignoredUpdates;

    /** 缓存的 Bot 用户名，用于判断群聊中是否 @ 了本 Bot */
    private volatile String botUsername = "";

    /**
     * 构造函数，注入所有依赖。
     *
     * @param properties       Telegram 配置属性
     * @param botClient        Bot API 客户端
     * @param deliveryStore    投递记录存储
     * @param bindingStore     研究组绑定存储
     * @param workflowRunService 工作流运行服务
     * @param notificationQueue  通知队列
     */
    public TelegramUpdatePoller(
            TelegramProperties properties,
            TelegramBotClient botClient,
            TelegramDeliveryStore deliveryStore,
            TelegramResearchGroupBindingStore bindingStore,
            WorkflowRunService workflowRunService,
            TelegramNotificationQueue notificationQueue) {
        this.properties = properties;
        this.botClient = botClient;
        this.deliveryStore = deliveryStore;
        this.bindingStore = bindingStore;
        this.workflowRunService = workflowRunService;
        this.notificationQueue = notificationQueue;
    }

    /**
     * 应用启动后自动开始轮询。
     * <p>通过 @EventListener(ApplicationReadyEvent.class) 触发，
     * 确保在 Spring 容器完全初始化后再开始拉取消息。
     * 仅在 pollingEnabled=true 且配置有效时启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.pollingEnabled()) {
            log.info("Telegram polling disabled");
            return;
        }
        if (!properties.hasBotToken()) {
            lastError = properties.botConfigError();
            log.warn(lastError);
            return;
        }
        if (!properties.hasProxyConfig()) {
            lastError = properties.proxyConfigError();
            log.warn(lastError);
            return;
        }
        if (running.compareAndSet(false, true)) {
            executor.submit(this::pollLoop);
        }
    }

    /**
     * 应用关闭前停止轮询。
     * <p>通过 @PreDestroy 注解触发，设置 running 为 false 并中断轮询线程。
     */
    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    /** 轮询是否正在运行 */
    public boolean isRunning() {
        return running.get();
    }

    /** 获取最后一次成功轮询的时间 */
    public Instant getLastPollAt() {
        return lastPollAt;
    }

    /** 获取最后一次错误信息 */
    public String getLastError() {
        return lastError;
    }

    /** 获取已处理的消息总数 */
    public long getProcessedUpdates() {
        return processedUpdates;
    }

    /** 获取已忽略的消息总数 */
    public long getIgnoredUpdates() {
        return ignoredUpdates;
    }

    /** 获取下一次拉取的 offset */
    public Long getNextOffset() {
        return nextOffset;
    }

    /** 获取缓存的 Bot 用户名 */
    public String getBotUsername() {
        return botUsername;
    }

    /**
     * 核心轮询循环。
     * <p>持续调用 Telegram getUpdates API 拉取新消息，逐条处理。
     * 每次拉取后更新 nextOffset，确保下次拉取从正确位置开始。
     * 发生异常时等待 3 秒后重试。
     */
    private void pollLoop() {
        try {
            initializePolling();
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Telegram polling initialization failed | error={}", e.getMessage());
        }

        while (running.get()) {
            try {
                JsonNode updates = botClient.getUpdates(nextOffset,
                        properties.pollingTimeoutSeconds(),
                        properties.pollingLimit());
                lastPollAt = Instant.now();
                lastError = "";
                for (JsonNode update : updates) {
                    long updateId = update.path("update_id").asLong();
                    nextOffset = updateId + 1;
                    handleUpdate(update);
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("Telegram polling failed | error={}", e.getMessage());
                sleepQuietly(3000);
            }
        }
    }

    /**
     * 初始化轮询环境。
     * <p>执行以下步骤：
     * <ol>
     *   <li>删除 Webhook（确保 Long Polling 模式正常工作）；</li>
     *   <li>调用 getMe 获取 Bot 信息并缓存用户名；</li>
     *   <li>如果配置了跳过旧消息，拉取并丢弃所有积压消息。</li>
     * </ol>
     */
    private void initializePolling() {
        botClient.deleteWebhook(false);
        JsonNode me = botClient.getMe();
        botUsername = me.path("username").asText("");
        log.info("Telegram polling ready | botUsername={} | skipOldUpdates={}",
                botUsername, properties.skipOldUpdatesOnStart());

        if (properties.skipOldUpdatesOnStart()) {
            JsonNode pendingUpdates = botClient.getUpdates(null, 1, 100);
            long maxUpdateId = -1L;
            for (JsonNode update : pendingUpdates) {
                maxUpdateId = Math.max(maxUpdateId, update.path("update_id").asLong(-1L));
            }
            if (maxUpdateId >= 0) {
                nextOffset = maxUpdateId + 1;
                ignoredUpdates += pendingUpdates.size();
                log.info("Telegram old updates skipped | count={} | nextOffset={}",
                        pendingUpdates.size(), nextOffset);
            }
        }
    }

    /**
     * 处理单条 Telegram 更新消息。
     *
     * <p>处理流程：
     * <ol>
     *   <li>解析消息为 TelegramInboundMessage；</li>
     *   <li>检查是否已处理过（去重）；</li>
     *   <li>如果是命令（/help、/use、/status 等），执行对应逻辑；</li>
     *   <li>如果是普通问题消息，触发 RAG 工作流；</li>
     *   <li>将工作流 ID 加入通知队列，等待结果完成后回复。</li>
     * </ol>
     *
     * @param update Telegram API 返回的单条更新 JSON
     */
    private void handleUpdate(JsonNode update) {
        try {
            // 步骤1：解析消息
            TelegramInboundMessage message = parseMessage(update);
            if (message == null || isBlank(message.text)) {
                ignoredUpdates++;
                return;
            }
            // 步骤2：消息去重（防止重复处理同一条消息）
            if (deliveryStore.findWorkflowByUpdateId(message.updateId) != null) {
                ignoredUpdates++;
                return;
            }

            String text = message.text.trim();
            // 去除 @mention 后检查是否为命令
            String commandText = stripBotMention(text);
            // 步骤3：处理各类命令
            if (isHelpCommand(commandText)) {
                sendHelp(message.chatId);
                processedUpdates++;
                return;
            }
            if (isBindingCommand(commandText)) {
                handleBindingCommand(message, commandText);
                processedUpdates++;
                return;
            }
            if (isStatusCommand(commandText)) {
                sendStatus(message);
                processedUpdates++;
                return;
            }
            // 步骤4：判断是否应该响应（私聊直接响应，群聊需要 @ 或 /ask）
            if (!shouldRespond(message)) {
                ignoredUpdates++;
                return;
            }

            // 步骤5：规范化文本（去除命令前缀和 @mention）
            message.text = normalizeText(text);
            if (isBlank(message.text)) {
                sendHelp(message.chatId);
                processedUpdates++;
                return;
            }

            // 步骤6：构建工作流请求
            WorkflowRunRequest request = new WorkflowRunRequest();
            request.engine = "auto";      // 自动选择 RAG 引擎
            request.profile = "auto";     // 自动选择处理配置
            request.source = "telegram";  // 标记消息来源为 Telegram
            request.userId = "telegram:user:" + message.fromId;
            request.conversationId = "telegram:chat:" + message.chatId;
            // 步骤7：解析研究组（决定使用哪个知识库）
            TelegramResearchGroupResolution resolution = bindingStore.resolve(message);
            request.researchGroupId = resolution.researchGroupId;
            request.message = message.text;

            // 步骤8：触发 RAG 工作流
            WorkflowRunSnapshot snapshot = workflowRunService.startRun(request);
            // 步骤9：记录投递信息
            deliveryStore.recordInbound(message, snapshot.workflowId, resolution);
            // 步骤10：发送"已收到"确认消息
            sendAccepted(message.chatId, snapshot.workflowId);
            // 步骤11：将工作流 ID 加入通知队列（异步等待完成后回复用户）
            notificationQueue.enqueue(message.chatId, snapshot.workflowId);
            processedUpdates++;
            log.info("Telegram workflow started | workflowId={} | updateId={} | chatId={} | fromId={} | researchGroupId={} | source={}",
                    snapshot.workflowId, message.updateId, message.chatId, message.fromId,
                    resolution.researchGroupId, resolution.source);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Telegram update handling failed | error={}", e.getMessage());
        }
    }

    /**
     * 从 Telegram Update JSON 中解析出消息对象。
     *
     * @param update Telegram API 返回的单条更新 JSON
     * @return 解析后的消息对象，如果更新不包含文本消息则返回 null
     */
    private TelegramInboundMessage parseMessage(JsonNode update) {
        JsonNode messageNode = update.path("message");
        if (messageNode.isMissingNode() || messageNode.isNull()) {
            return null;
        }
        String text = messageNode.path("text").asText("");
        if (isBlank(text)) {
            return null;
        }

        JsonNode chat = messageNode.path("chat");
        JsonNode from = messageNode.path("from");
        TelegramInboundMessage message = new TelegramInboundMessage();
        message.updateId = update.path("update_id").asLong();
        message.messageId = messageNode.path("message_id").asLong();
        message.chatId = chat.path("id").asLong();
        message.fromId = from.path("id").asLong();
        message.chatType = chat.path("type").asText("");
        message.username = from.path("username").asText("");
        message.firstName = from.path("first_name").asText("");
        message.text = text;
        return message;
    }

    /**
     * 判断系统是否应该响应该消息。
     * <p>规则：
     * <ul>
     *   <li>私聊消息始终响应；</li>
     *   <li>群聊中以 /ask 开头的消息响应；</li>
     *   <li>群聊中 @ 了本 Bot 的消息响应。</li>
     * </ul>
     *
     * @param message 收到的消息
     * @return 是否应该响应
     */
    private boolean shouldRespond(TelegramInboundMessage message) {
        if ("private".equalsIgnoreCase(message.chatType)) {
            return true;
        }
        String text = message.text.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("/ask")) {
            return true;
        }
        String username = effectiveBotUsername();
        return !isBlank(username) && text.contains("@" + username.toLowerCase(Locale.ROOT));
    }

    /**
     * 判断是否为帮助命令（/start 或 /help）。
     */
    private boolean isHelpCommand(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("/start")
                || normalized.equals("/help")
                || normalized.startsWith("/start@")
                || normalized.startsWith("/help@");
    }

    /**
     * 规范化消息文本：去除 @mention、/ask 命令前缀和首尾空白。
     */
    private String normalizeText(String text) {
        String cleaned = stripBotMention(text);
        cleaned = cleaned.trim();
        cleaned = cleaned.replaceFirst("(?i)^/ask\\s*", "");
        return cleaned.trim();
    }

    /**
     * 去除消息中的 @botname 提及。
     */
    private String stripBotMention(String text) {
        String cleaned = text == null ? "" : text.trim();
        String username = effectiveBotUsername();
        if (!isBlank(username)) {
            cleaned = cleaned.replaceAll("(?i)@" + Pattern.quote(username), "");
        }
        return cleaned.trim();
    }

    /**
     * 发送帮助信息到指定聊天。
     */
    private void sendHelp(long chatId) {
        botClient.sendPlainText(chatId, """
                Hello, I am your research Agent.

                In private chat, send your question directly.
                In group chat, use: /ask your question

                Example:
                /ask What is next-POI recommendation?

                Library commands:
                /current - show current literature library
                /status - show recent Agent task and queue status
                /libs - show allowed libraries
                /use replace-me - switch the default library
                /use_user replace-me - bind your Telegram user id
                /use_chat replace-me - bind this chat or group
                /allow replace-me - add an allowed library id
                """);
    }

    /**
     * 判断是否为研究组绑定相关命令。
     */
    private boolean isBindingCommand(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("/current")
                || normalized.equals("/libs")
                || normalized.startsWith("/use ")
                || normalized.startsWith("/use_user ")
                || normalized.startsWith("/use_chat ")
                || normalized.startsWith("/allow ")
                || normalized.startsWith("/allow_user ")
                || normalized.startsWith("/allow_chat ");
    }

    /**
     * 判断是否为状态查看命令（/status）。
     */
    private boolean isStatusCommand(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("/status") || normalized.startsWith("/status@");
    }

    /**
     * 处理研究组绑定命令（/use、/allow、/current、/libs 等）。
     *
     * @param message 收到的消息
     * @param text    命令文本
     */
    private void handleBindingCommand(TelegramInboundMessage message, String text) {
        String normalized = text.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals("/current")) {
            sendCurrentBinding(message);
            return;
        }
        if (lower.equals("/libs")) {
            sendLibraryList(message);
            return;
        }

        String[] parts = normalized.split("\\s+", 2);
        if (parts.length < 2 || isBlank(parts[1])) {
            botClient.sendPlainText(message.chatId,
                    "Please provide a researchGroupId, for example:\n/use replace-me");
            return;
        }

        String command = parts[0].toLowerCase(Locale.ROOT);
        String researchGroupId = parts[1].trim();
        String updatedBy = "telegram:user:" + message.fromId;
        TelegramResearchGroupBinding binding;
        switch (command) {
            case "/use_user" -> binding = bindingStore.bindUser(message.fromId, researchGroupId, updatedBy);
            case "/use_chat" -> binding = bindingStore.bindChat(message.chatId, researchGroupId, updatedBy);
            case "/allow_user" -> binding = bindingStore.allowForUser(message.fromId, researchGroupId, updatedBy);
            case "/allow_chat" -> binding = bindingStore.allowForChat(message.chatId, researchGroupId, updatedBy);
            case "/allow" -> binding = isPrivateChat(message)
                    ? bindingStore.allowForUser(message.fromId, researchGroupId, updatedBy)
                    : bindingStore.allowForChat(message.chatId, researchGroupId, updatedBy);
            default -> binding = isPrivateChat(message)
                    ? bindingStore.bindUser(message.fromId, researchGroupId, updatedBy)
                    : bindingStore.bindChat(message.chatId, researchGroupId, updatedBy);
        }

        botClient.sendPlainText(message.chatId,
                "Library binding updated.\n"
                        + "scope: " + binding.scope + "\n"
                        + "default: " + binding.defaultResearchGroupId + "\n"
                        + "allowed: " + String.join(", ", binding.allowedResearchGroupIds));
    }

    /**
     * 发送当前绑定信息（/current 命令的处理逻辑）。
     */
    private void sendCurrentBinding(TelegramInboundMessage message) {
        TelegramResearchGroupResolution resolution = bindingStore.resolve(message);
        botClient.sendPlainText(message.chatId,
                "Current literature library:\n"
                        + "researchGroupId: " + resolution.researchGroupId + "\n"
                        + "source: " + resolution.source + "\n"
                        + "telegram from.id: " + message.fromId + "\n"
                        + "telegram chat.id: " + message.chatId);
    }

    /**
     * 发送允许使用的研究组列表（/libs 命令的处理逻辑）。
     */
    private void sendLibraryList(TelegramInboundMessage message) {
        TelegramResearchGroupResolution resolution = bindingStore.resolve(message);
        botClient.sendPlainText(message.chatId,
                "Allowed literature libraries:\n"
                        + String.join("\n", resolution.allowedResearchGroupIds)
                        + "\n\nCurrent: " + resolution.researchGroupId);
    }

    /**
     * 发送系统状态信息（/status 命令的处理逻辑）。
     */
    private void sendStatus(TelegramInboundMessage message) {
        TelegramResearchGroupResolution resolution = bindingStore.resolve(message);
        StringBuilder sb = new StringBuilder();
        sb.append("Agent status\n")
                .append("current researchGroupId: ").append(resolution.researchGroupId).append("\n")
                .append("binding source: ").append(resolution.source).append("\n")
                .append("queue: ").append(notificationQueue.status()).append("\n")
                .append("recent deliveries:\n");
        var deliveries = deliveryStore.recentDeliveries(5).stream()
                .filter(item -> item.chatId == message.chatId || item.fromId == message.fromId)
                .toList();
        if (deliveries.isEmpty()) {
            sb.append("- none\n");
        } else {
            for (TelegramDeliveryRecord record : deliveries) {
                sb.append("- ").append(record.workflowId)
                        .append(" / ").append(record.status)
                        .append(" / ").append(record.queueMode == null ? "direct" : record.queueMode)
                        .append("\n");
            }
        }
        botClient.sendPlainText(message.chatId, sb.toString());
    }

    /** 判断是否为私聊 */
    private boolean isPrivateChat(TelegramInboundMessage message) {
        return "private".equalsIgnoreCase(message.chatType);
    }

    /**
     * 发送"已收到"的确认消息给用户。
     */
    private void sendAccepted(long chatId, String workflowId) {
        String link = botClient.publicWorkflowLink(workflowId);
        TelegramSendResult result = botClient.sendPlainText(chatId,
                "Received. The Agent is processing your request:\n"
                        + link
                        + "\nI will send the full answer when it is ready.");
        if (!result.success) {
            log.warn("Telegram accepted message failed | workflowId={} | error={}",
                    workflowId, result.errorMessage);
        }
    }

    /**
     * 获取有效的 Bot 用户名（优先使用缓存，否则从 botClient 获取）。
     */
    private String effectiveBotUsername() {
        if (!isBlank(botUsername)) {
            return botUsername;
        }
        return botClient.botUsername();
    }

    /** 判断字符串是否为 null 或空白 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 静默休眠，被中断时设置 running 为 false 并恢复中断状态。
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }
}
