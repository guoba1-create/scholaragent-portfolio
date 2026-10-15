package com.team.rag.controller;

import com.team.rag.entity.Conversation;
import com.team.rag.identity.RequestIdentityResolver;
import com.team.rag.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对话控制器 - 管理用户对话的 CRUD 操作。
 *
 * <p>该控制器提供对话的完整生命周期管理功能，包括创建对话、
 * 查询对话列表、获取对话详情、添加消息、更新标题和删除对话等操作。</p>
 *
 * <h3>REST 端点概述：</h3>
 * <ul>
 *   <li>{@code POST /api/conversations/create} - 创建新对话</li>
 *   <li>{@code GET /api/conversations/list} - 获取用户对话列表</li>
 *   <li>{@code GET /api/conversations/{conversationId}} - 获取对话详情</li>
 *   <li>{@code POST /api/conversations/{conversationId}/messages} - 添加消息</li>
 *   <li>{@code PUT /api/conversations/{conversationId}/title} - 更新对话标题</li>
 *   <li>{@code DELETE /api/conversations/{conversationId}} - 删除对话</li>
 *   <li>{@code DELETE /api/conversations/clear} - 清空用户所有对话</li>
 * </ul>
 *
 * @see ConversationService
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    /** 对话服务，提供对话的核心业务逻辑 */
    private final ConversationService conversationService;
    private final RequestIdentityResolver identityResolver;

    /**
     * 构造函数，注入对话服务。
     *
     * @param conversationService 对话服务
     */
    public ConversationController(
            ConversationService conversationService,
            RequestIdentityResolver identityResolver) {
        this.conversationService = conversationService;
        this.identityResolver = identityResolver;
    }

    /**
     * 创建新对话。
     *
     * <p>为指定用户创建一个新的对话，可选择性地设置对话标题。
     * 如果不提供标题，默认使用 "New conversation"。</p>
     *
     * <p>HTTP 方法：POST</p>
     * <p>路径：/api/conversations/create</p>
     *
     * @param userId 用户 ID，必填
     * @param title  对话标题（可选），默认为 "New conversation"
     * @return 包含对话 ID、标题和创建时间的响应
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createConversation(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @RequestParam(value = "title", required = false) String title,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();

        // 处理标题，如果为空则使用默认标题
        String conversationTitle = isBlank(title) ? "New conversation" : title.trim();
        // 创建对话
        Conversation conversation = conversationService.createConversation(userId, conversationTitle);
        log.info("Conversation created | userId={}, conversationId={}", userId, conversation.getId());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "conversationId", conversation.getId(),
                "title", conversation.getTitle(),
                "createdAt", conversation.getCreatedAt().toString()));
    }

    /**
     * 获取用户对话列表。
     *
     * <p>查询指定用户的所有对话列表，返回每个对话的基本信息，
     * 包括 ID、标题、消息数量、创建时间和更新时间。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/conversations/list</p>
     *
     * @param userId 用户 ID，必填
     * @return 包含对话列表的响应
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getUserConversations(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();

        // 获取用户对话列表并转换为响应格式
        List<Map<String, Object>> conversationList = conversationService.getUserConversations(userId)
                .stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "title", c.getTitle(),
                        "messageCount", c.getMessages().size(),
                        "createdAt", c.getCreatedAt().toString(),
                        "updatedAt", c.getUpdatedAt().toString()))
                .toList();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "conversations", conversationList));
    }

    /**
     * 获取对话详情。
     *
     * <p>根据对话 ID 获取对话的完整信息，包括所有消息历史。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/conversations/{conversationId}</p>
     *
     * @param userId         用户 ID，必填
     * @param conversationId 对话 ID，路径参数
     * @return 包含对话详情的响应，如果对话不存在返回失败信息
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> getConversation(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @PathVariable("conversationId") String conversationId,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();

        // 获取对话详情
        Conversation conversation = conversationService.getConversation(userId, conversationId);
        if (conversation == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "conversation not found"));
        }

        // 返回对话详情，包括消息历史
        return ResponseEntity.ok(Map.of(
                "success", true,
                "conversation", Map.of(
                        "id", conversation.getId(),
                        "title", conversation.getTitle(),
                        "messages", conversation.getMessages(),
                        "createdAt", conversation.getCreatedAt().toString(),
                        "updatedAt", conversation.getUpdatedAt().toString())));
    }

    /**
     * 添加消息到对话。
     *
     * <p>向指定对话中添加一条新消息，消息包含角色（user/assistant）和内容。</p>
     *
     * <p>HTTP 方法：POST</p>
     * <p>路径：/api/conversations/{conversationId}/messages</p>
     *
     * @param userId         用户 ID，必填
     * @param conversationId 对话 ID，路径参数
     * @param request        请求体，包含 role（角色）和 content（内容）
     * @return 保存结果响应
     */
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<Map<String, Object>> addMessage(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @PathVariable("conversationId") String conversationId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();
        // 提取消息参数
        String role = body == null ? null : body.get("role");
        String content = body == null ? null : body.get("content");
        // 验证消息参数
        if (isBlank(role) || isBlank(content)) {
            return badRequest("role and content are required");
        }

        // 添加消息到对话
        conversationService.addMessage(userId, conversationId, role, content);
        return ResponseEntity.ok(Map.of("success", true, "message", "message saved"));
    }

    /**
     * 更新对话标题。
     *
     * <p>修改指定对话的标题，需要提供新的标题内容。</p>
     *
     * <p>HTTP 方法：PUT</p>
     * <p>路径：/api/conversations/{conversationId}/title</p>
     *
     * @param userId         用户 ID，必填
     * @param conversationId 对话 ID，路径参数
     * @param request        请求体，包含 title（新标题）
     * @return 更新结果响应
     */
    @PutMapping("/{conversationId}/title")
    public ResponseEntity<Map<String, Object>> updateTitle(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @PathVariable("conversationId") String conversationId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();
        // 提取标题参数
        String title = body == null ? null : body.get("title");
        if (isBlank(title)) {
            return badRequest("title is required");
        }

        // 获取对话
        Conversation conversation = conversationService.getConversation(userId, conversationId);
        if (conversation == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "conversation not found"));
        }

        // 更新标题并保存
        conversation.setTitle(title.trim());
        conversationService.saveConversation(conversation);
        return ResponseEntity.ok(Map.of("success", true, "message", "title updated"));
    }

    /**
     * 删除对话。
     *
     * <p>删除指定用户的指定对话，包括对话中的所有消息。</p>
     *
     * <p>HTTP 方法：DELETE</p>
     * <p>路径：/api/conversations/{conversationId}</p>
     *
     * @param userId         用户 ID，必填
     * @param conversationId 对话 ID，路径参数
     * @return 删除结果响应
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @PathVariable("conversationId") String conversationId,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();

        // 删除对话
        conversationService.deleteConversation(userId, conversationId);
        log.info("Conversation deleted | userId={}, conversationId={}", userId, conversationId);
        return ResponseEntity.ok(Map.of("success", true, "message", "conversation deleted"));
    }

    /**
     * 清空用户所有对话。
     *
     * <p>删除指定用户的所有对话，用于批量清理操作。</p>
     *
     * <p>HTTP 方法：DELETE</p>
     * <p>路径：/api/conversations/clear</p>
     *
     * @param userId 用户 ID，必填
     * @return 清空结果响应
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearConversations(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();

        // 清空用户所有对话
        conversationService.clearUserConversations(userId);
        log.info("Conversations cleared | userId={}", userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "conversations cleared"));
    }

    /**
     * 返回 400 错误响应。
     *
     * @param message 错误信息
     * @return 包含错误信息的 ResponseEntity
     */
    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }

    /**
     * 检查字符串是否为空或空白。
     *
     * @param value 待检查的字符串
     * @return 如果字符串为 null 或空白返回 true，否则返回 false
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
