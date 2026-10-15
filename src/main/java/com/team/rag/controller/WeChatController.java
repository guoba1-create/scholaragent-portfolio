package com.team.rag.controller;

import com.team.rag.agent.runtime.WorkflowRunRequest;
import com.team.rag.agent.runtime.WorkflowRunService;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import com.team.rag.wechat.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信集成控制器 —— 处理微信公众号的回调验证、消息接收和异步回复。
 *
 * <p>本控制器实现了微信公众号的服务器端接入，遵循微信的消息签名验证机制。
 * 用户通过微信公众号发送文本消息后，系统启动异步工作流处理，
 * 处理完成后通过微信模板消息或客服接口回复用户。</p>
 *
 * <h3>REST 端点列表</h3>
 * <table border="1">
 *   <tr><th>HTTP 方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/wechat/status</td><td>微信集成配置状态概览</td></tr>
 *   <tr><td>GET</td><td>/api/wechat/deliveries</td><td>查看最近的消息投递记录</td></tr>
 *   <tr><td>GET</td><td>/api/wechat/deliveries/{id}</td><td>按 workflowId 查询投递记录</td></tr>
 *   <tr><td>GET</td><td>/api/wechat/callback</td><td>微信服务器 URL 验证（echostr 回显）</td></tr>
 *   <tr><td>POST</td><td>/api/wechat/callback</td><td>接收微信推送的消息和事件</td></tr>
 * </table>
 *
 * @see WeChatProperties 微信配置属性
 * @see WeChatSignatureService 微信签名验证服务
 * @see WeChatMessageAdapter 微信消息解析适配器
 * @see WorkflowRunService 工作流运行服务
 */
@RestController
@RequestMapping("/api/wechat")
public class WeChatController {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(WeChatController.class);

    /** 微信配置属性（AppID、AppSecret、Token、AES Key 等） */
    private final WeChatProperties properties;

    /** 微信签名验证服务，用于验证请求来源的合法性 */
    private final WeChatSignatureService signatureService;

    /** 微信消息解析适配器，将 XML 格式的微信消息解析为内部对象 */
    private final WeChatMessageAdapter messageAdapter;

    /** 工作流运行服务，用于启动 RAG Agent 异步处理流程 */
    private final WorkflowRunService workflowRunService;

    /** 微信会话存储，记录入站消息和投递状态 */
    private final WeChatSessionStore sessionStore;

    /** 微信工作流通知器，负责在工作流完成后回复用户 */
    private final WeChatWorkflowNotifier notifier;

    /**
     * 构造函数，通过 Spring 依赖注入初始化各组件。
     *
     * @param properties       微信配置属性
     * @param signatureService 签名验证服务
     * @param messageAdapter   消息解析适配器
     * @param workflowRunService 工作流运行服务
     * @param sessionStore     会话存储
     * @param notifier         工作流完成通知器
     */
    public WeChatController(
            WeChatProperties properties,
            WeChatSignatureService signatureService,
            WeChatMessageAdapter messageAdapter,
            WorkflowRunService workflowRunService,
            WeChatSessionStore sessionStore,
            WeChatWorkflowNotifier notifier) {
        this.properties = properties;
        this.signatureService = signatureService;
        this.messageAdapter = messageAdapter;
        this.workflowRunService = workflowRunService;
        this.sessionStore = sessionStore;
        this.notifier = notifier;
    }

    /**
     * 微信集成状态概览端点 —— 返回配置状态和运行信息。
     *
     * <p>包括回调配置、回复配置、AES Key 配置、公网地址配置等状态，
     * 以及最近的消息投递记录和必需的环境变量参考。</p>
     *
     * @return 包含所有配置和状态信息的 Map
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 各项配置的就绪状态和错误信息
        result.put("callbackConfigured", properties.hasCallbackConfig());
        result.put("replyConfigured", properties.hasReplyConfig());
        result.put("publicBaseUrlConfigured", properties.hasPublicAppBaseUrl());
        result.put("aesKeyConfigured", properties.hasAesKey());
        result.put("callbackConfigError", properties.callbackConfigError());
        result.put("replyConfigError", properties.replyConfigError());
        result.put("publicBaseUrlConfigError", properties.publicBaseUrlConfigError());
        result.put("aesKeyConfigError", properties.aesKeyConfigError());

        // 关键 URL 信息
        result.put("publicAppBaseUrl", properties.publicAppBaseUrl());
        result.put("callbackUrl", properties.publicAppBaseUrl() + "/api/wechat/callback");
        result.put("agentConsoleUrl", properties.publicAppBaseUrl() + "/app/");

        // 消息处理模式
        result.put("messageMode", "plaintext");
        result.put("asyncReply", true);

        // 最近投递记录
        result.put("recentDeliveries", sessionStore.recentDeliveries(5));

        // 环境变量参考
        result.put("requiredEnvironment", Map.of(
                "WECHAT_APP_ID", "official account app id",
                "WECHAT_APP_SECRET", "official account app secret",
                "WECHAT_TOKEN", "same token configured in WeChat admin",
                "WECHAT_AES_KEY", "43-character EncodingAESKey configured in WeChat admin",
                "PUBLIC_APP_BASE_URL", "current tunnel or public HTTPS base URL"
        ));
        return ResponseEntity.ok(result);
    }

    /**
     * 查看最近投递记录 —— 获取微信消息投递的历史记录。
     *
     * @param limit 最大返回数量（默认 30）
     * @return 投递记录列表
     */
    @GetMapping("/deliveries")
    public ResponseEntity<List<WeChatDeliveryRecord>> deliveries(
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return ResponseEntity.ok(sessionStore.recentDeliveries(limit));
    }

    /**
     * 按 workflowId 查询投递记录 —— 查看单次工作流的投递详情。
     *
     * @param workflowId 工作流 ID
     * @return 投递记录，未找到时返回 404
     */
    @GetMapping("/deliveries/{workflowId}")
    public ResponseEntity<WeChatDeliveryRecord> delivery(@PathVariable String workflowId) {
        WeChatDeliveryRecord record = sessionStore.getDelivery(workflowId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /**
     * 微信服务器 URL 验证端点 —— 处理微信服务器的 GET 验证请求。
     *
     * <p>微信公众号接入时，服务器会发送 GET 请求验证 URL 的有效性。
     * 验证通过后需原样返回 echostr 参数。</p>
     *
     * @param signature 微信加密签名
     * @param timestamp 时间戳
     * @param nonce     随机数
     * @param echostr   随机字符串（验证通过后需原样返回）
     * @return 验证通过返回 echostr，失败返回 403
     */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam(value = "signature", required = false) String signature,
            @RequestParam(value = "timestamp", required = false) String timestamp,
            @RequestParam(value = "nonce", required = false) String nonce,
            @RequestParam(value = "echostr", required = false) String echostr) {
        log.info("WeChat verify request | signature={} | timestamp={} | nonce={} | echostr={}",
                signature, timestamp, nonce, echostr);

        // 检查回调配置是否就绪
        if (!properties.hasCallbackConfig()) {
            log.warn("WeChat callback not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(properties.callbackConfigError());
        }

        // 验证签名：使用 Token + timestamp + nonce 计算 SHA1 并与 signature 比对
        boolean verified = signatureService.verify(signature, timestamp, nonce);
        log.info("WeChat signature verification result: {}", verified);
        if (verified) {
            // 验证通过，原样返回 echostr
            return ResponseEntity.ok(echostr == null ? "" : echostr);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("invalid wechat signature");
    }

    /**
     * 微信消息接收端点 —— 处理微信服务器推送的用户消息（POST）。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>验证消息签名的合法性</li>
     *   <li>解析 XML 格式的微信消息</li>
     *   <li>仅处理文本消息，忽略其他类型</li>
     *   <li>去重检查（同一消息不重复处理）</li>
     *   <li>启动异步工作流处理</li>
     *   <li>注册完成回调，处理完后自动回复用户</li>
     *   <li>立即返回 "success"（微信要求 5 秒内响应）</li>
     * </ol>
     *
     * @param signature 微信加密签名
     * @param timestamp 时间戳
     * @param nonce     随机数
     * @param body      微信推送的 XML 消息体
     * @return 固定返回 "success"（微信协议要求）
     */
    @PostMapping(value = "/callback",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(
            @RequestParam(value = "signature", required = false) String signature,
            @RequestParam(value = "timestamp", required = false) String timestamp,
            @RequestParam(value = "nonce", required = false) String nonce,
            @RequestBody String body) {
        // 检查回调配置
        if (!properties.hasCallbackConfig()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(properties.callbackConfigError());
        }
        // 验证签名
        if (!signatureService.verify(signature, timestamp, nonce)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("invalid wechat signature");
        }

        try {
            // 解析 XML 消息体
            WeChatInboundMessage message = messageAdapter.parse(body);

            // 仅处理文本消息，忽略图片、语音等其他类型
            if (!message.isText()) {
                log.info("Ignoring non-text WeChat message | type={}", message.msgType);
                return ResponseEntity.ok("success");
            }

            // 消息去重：检查该消息是否已处理过
            String duplicateWorkflowId = sessionStore.findWorkflowByMessage(message.fromUserName, message.msgId);
            if (duplicateWorkflowId != null) {
                log.info("Ignoring duplicate WeChat message | workflowId={} | msgId={}",
                        duplicateWorkflowId, message.msgId);
                return ResponseEntity.ok("success");
            }

            // 构建工作流请求
            WorkflowRunRequest request = new WorkflowRunRequest();
            request.engine = "auto";                              // 自动选择引擎
            request.profile = "auto";                             // 自动选择配置
            request.source = "wechat";                            // 标记来源为微信
            request.userId = "wechat:" + message.fromUserName;    // 以微信 OpenID 作为用户标识
            request.researchGroupId = "wechat:" + message.fromUserName;
            request.conversationId = "wechat:open:" + message.fromUserName;
            request.message = message.content;                    // 用户发送的文本内容

            // 启动异步工作流
            WorkflowRunSnapshot snapshot = workflowRunService.startRun(request);

            // 记录入站消息和工作流关联
            sessionStore.recordInbound(message.fromUserName, message.msgId, message.content, snapshot.workflowId);
            log.info("WeChat workflow started | workflowId={} | msgId={} | openId={}",
                    snapshot.workflowId, message.msgId, mask(message.fromUserName));

            // 注册完成回调：工作流结束后自动向用户发送回复
            notifier.notifyWhenComplete(message.fromUserName, snapshot.workflowId);

            return ResponseEntity.ok("success");
        } catch (Exception e) {
            log.error("Failed to receive WeChat message | error={}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid wechat message");
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 脱敏处理：将敏感字符串中间部分替换为星号。
     * <p>用于日志输出时保护用户隐私（如微信 OpenID）。</p>
     *
     * @param value 原始字符串
     * @return 脱敏后的字符串（保留首尾各 4 个字符）
     */
    private String mask(String value) {
        if (value == null || value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
