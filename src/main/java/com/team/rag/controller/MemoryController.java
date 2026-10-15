package com.team.rag.controller;

import com.team.rag.agent.runtime.MemoryRecallRecord;
import com.team.rag.config.RedisChatMemoryStore;
import com.team.rag.agent.routing.HybridRouteDecision;
import com.team.rag.identity.RequestIdentity;
import com.team.rag.identity.RequestIdentityResolver;
import com.team.rag.service.DualEngineMemoryManager;
import com.team.rag.service.MemoryRecallService;
import com.team.rag.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆控制器 —— 管理用户的多层记忆系统（结构化档案、语义记忆、短期对话摘要）。
 *
 * <p>本控制器是 RAG 系统记忆管理的前端入口，实现了三层记忆架构：</p>
 * <ul>
 *   <li><b>结构化档案（Structured Profile）</b>：用户的基本属性信息（如研究方向、偏好等），以键值对存储</li>
 *   <li><b>语义记忆（Semantic Memory）</b>：基于向量存储的长期记忆，支持语义检索</li>
 *   <li><b>短期对话摘要（Short-term Summary）</b>：当前对话的压缩摘要，用于上下文窗口管理</li>
 * </ul>
 *
 * <h3>REST 端点列表</h3>
 * <table border="1">
 *   <tr><th>HTTP 方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/memory/structured</td><td>保存用户结构化档案</td></tr>
 *   <tr><td>POST</td><td>/api/memory/semantic</td><td>保存语义记忆条目</td></tr>
 *   <tr><td>GET</td><td>/api/memory/structured</td><td>获取用户结构化档案</td></tr>
 *   <tr><td>GET</td><td>/api/memory/semantic</td><td>获取/搜索用户语义记忆</td></tr>
 *   <tr><td>GET</td><td>/api/memory/summary</td><td>获取短期对话摘要</td></tr>
 *   <tr><td>GET</td><td>/api/memory/all</td><td>获取用户的全部记忆（三层合一）</td></tr>
 *   <tr><td>POST</td><td>/api/memory/recall</td><td>执行记忆召回（综合三层记忆的检索）</td></tr>
 * </table>
 *
 * @see DualEngineMemoryManager 双引擎记忆管理器
 * @see RedisChatMemoryStore Redis 聊天记忆存储
 * @see MemoryRecallService 记忆召回服务
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    /** 双引擎记忆管理器，管理结构化档案和语义记忆 */
    private final DualEngineMemoryManager memoryManager;

    /** Redis 聊天记忆存储，管理短期对话上下文和摘要 */
    private final RedisChatMemoryStore chatMemoryStore;

    /** 统计服务，用于记录记忆操作事件 */
    private final StatsService statsService;

    /** 记忆召回服务，综合多层记忆进行智能召回 */
    private final MemoryRecallService memoryRecallService;
    private final RequestIdentityResolver identityResolver;

    /**
     * 构造函数，通过 Spring 依赖注入初始化各服务组件。
     *
     * @param memoryManager      双引擎记忆管理器
     * @param chatMemoryStore    Redis 聊天记忆存储
     * @param statsService       统计服务
     * @param memoryRecallService 记忆召回服务
     */
    public MemoryController(
            DualEngineMemoryManager memoryManager,
            RedisChatMemoryStore chatMemoryStore,
            StatsService statsService,
            MemoryRecallService memoryRecallService,
            RequestIdentityResolver identityResolver) {
        this.memoryManager = memoryManager;
        this.chatMemoryStore = chatMemoryStore;
        this.statsService = statsService;
        this.memoryRecallService = memoryRecallService;
        this.identityResolver = identityResolver;
    }

    /**
     * 保存用户结构化档案 —— 以键值对形式存储用户的结构化属性信息。
     *
     * <p>结构化档案用于存储用户的固定属性，如研究方向、学术偏好、常用术语等。
     * 这些信息在对话中可被直接引用，无需语义检索。</p>
     *
     * @param userId      用户 ID（必需）
     * @param profileData 属性键值对，如 {"researchField": "深度学习", "level": "博士生"}
     * @return 操作结果，包含成功状态和保存的字段数量
     */
    @PostMapping("/structured")
    public ResponseEntity<Map<String, Object>> saveStructuredProfile(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @RequestBody Map<String, String> profileData,
            HttpServletRequest servletRequest) {
        String userId = identityResolver.resolve(servletRequest).userId();
        if (profileData == null || profileData.isEmpty()) {
            return badRequest("profile data is required");
        }

        // 保存结构化档案并记录统计事件
        memoryManager.saveStructuredProfile(userId, profileData);
        statsService.recordMemorySave(userId, "structured");
        log.info("Structured profile saved | userId={}, fields={}", userId, profileData.size());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "userId", userId,
                "fieldCount", profileData.size()));
    }

    /**
     * 保存语义记忆条目 —— 将一段文本作为语义记忆存储到向量库中。
     *
     * <p>语义记忆是一种长期记忆，支持基于语义相似度的检索。
     * 可指定可见性（private/public）和类型（personal_memory 等）。</p>
     *
     * @param userId  用户 ID（必需）
     * @param request 请求体，包含 content（必需）、researchGroupId、conversationId、
     *                sourceWorkflowId、type、visibility 等可选字段
     * @return 操作结果，包含生成的 memoryId 和内容长度
     */
    @PostMapping("/semantic")
    public ResponseEntity<Map<String, Object>> saveSemanticMemory(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @RequestBody Map<String, String> request,
            HttpServletRequest servletRequest) {
        RequestIdentity identity = identityResolver.resolve(servletRequest);
        String userId = identity.userId();
        // 提取必需的 content 字段
        String content = request == null ? null : request.get("content");
        if (isBlank(content)) {
            return badRequest("content is required");
        }

        // 保存语义记忆，支持丰富的可选参数
        String memoryId = memoryManager.saveSemanticMemory(
                userId,
                content,
                identity.knowledgeSpaceId(),
                value(request, "conversationId"),
                value(request, "sourceWorkflowId"),
                defaultString(value(request, "type"), "personal_memory"),    // 默认类型
                defaultString(value(request, "visibility"), "private"));     // 默认私有
        statsService.recordMemorySave(userId, "semantic");

        log.info("Semantic memory saved | userId={}, memoryId={}, length={}", userId, memoryId, content.length());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "userId", userId,
                "memoryId", memoryId,
                "contentLength", content.length()));
    }

    /**
     * 获取用户结构化档案 —— 查询指定用户的所有结构化属性。
     *
     * @param userId 用户 ID（必需）
     * @return 用户的结构化属性键值对
     */
    @GetMapping("/structured")
    public ResponseEntity<Map<String, Object>> getStructuredProfile(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            HttpServletRequest servletRequest) {
        String userId = identityResolver.resolve(servletRequest).userId();

        // 查询结构化档案并记录查询统计
        Map<Object, Object> profile = memoryManager.getStructuredProfile(userId);
        statsService.recordProfileQuery(userId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "userId", userId,
                "profile", profile));
    }

    /**
     * 获取/搜索用户语义记忆 —— 支持按语义查询搜索或直接获取全部语义记忆。
     *
     * <p>当提供 query 参数时，执行语义相似度搜索；
     * 不提供 query 时，返回用户最近的语义记忆列表。</p>
     *
     * @param userId     用户 ID（必需）
     * @param query      语义搜索查询文本（可选）
     * @param maxResults 最大返回数量（默认 20）
     * @param minScore   最低相似度阈值（默认 0.0）
     * @return 语义记忆列表
     */
    @GetMapping("/semantic")
    public ResponseEntity<Map<String, Object>> getSemanticMemories(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "maxResults", defaultValue = "20") int maxResults,
            @RequestParam(value = "minScore", defaultValue = "0.0") double minScore,
            HttpServletRequest servletRequest) {
        String userId = identityResolver.resolve(servletRequest).userId();

        // 根据是否提供查询参数决定使用语义搜索还是直接获取
        List<Map<String, Object>> memories = isBlank(query)
                ? memoryManager.getSemanticMemories(userId, maxResults)
                : memoryManager.searchSemanticMemories(userId, query, maxResults, minScore);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "userId", userId,
                "count", memories.size(),
                "memories", memories));
    }

    /**
     * 获取短期对话摘要 —— 查询当前对话的压缩摘要和消息统计。
     *
     * <p>短期摘要用于在对话上下文窗口溢出时保留关键信息。
     * memoryId 由 userId 和 conversationId 组合生成。</p>
     *
     * @param userId         用户 ID（必需）
     * @param conversationId 对话 ID（可选，默认为 userId + ":default"）
     * @return 对话摘要、消息数量和存储模式
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getShortTermSummary(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            HttpServletRequest servletRequest) {
        String userId = identityResolver.resolve(servletRequest).userId();

        // 构建 memoryId 并查询短期记忆
        String memoryId = buildMemoryId(userId, conversationId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("userId", userId);
        response.put("conversationId", defaultConversationId(userId, conversationId));
        response.put("memoryId", memoryId);
        response.put("summary", chatMemoryStore.getSummary(memoryId));
        response.put("messageCount", chatMemoryStore.getMessageCount(memoryId));
        response.put("storageMode", chatMemoryStore.getStorageMode());
        return ResponseEntity.ok(response);
    }

    /**
     * 获取用户全部记忆（三层合一） —— 一次性返回结构化档案、语义记忆和短期摘要。
     *
     * <p>此端点将三层记忆合并返回，适用于前端记忆管理页面的全量展示。
     * 语义记忆部分支持可选的语义搜索。</p>
     *
     * @param userId              用户 ID（必需）
     * @param conversationId      对话 ID（可选）
     * @param query               语义搜索查询文本（可选）
     * @param maxSemanticResults  语义记忆最大返回数量（默认 20）
     * @param minScore            最低相似度阈值（默认 0.0）
     * @return 包含三层记忆数据的完整响应
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllMemories(
            @RequestParam(value = "userId", required = false) String ignoredUserId,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "maxSemanticResults", defaultValue = "20") int maxSemanticResults,
            @RequestParam(value = "minScore", defaultValue = "0.0") double minScore,
            HttpServletRequest servletRequest) {
        String userId = identityResolver.resolve(servletRequest).userId();

        // 获取结构化档案
        Map<Object, Object> structuredProfile = memoryManager.getStructuredProfile(userId);

        // 获取/搜索语义记忆
        List<Map<String, Object>> semanticMemories = isBlank(query)
                ? memoryManager.getSemanticMemories(userId, maxSemanticResults)
                : memoryManager.searchSemanticMemories(userId, query, maxSemanticResults, minScore);

        // 获取短期对话摘要
        String memoryId = buildMemoryId(userId, conversationId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("userId", userId);
        response.put("conversationId", defaultConversationId(userId, conversationId));
        response.put("memoryId", memoryId);
        response.put("structuredProfile", structuredProfile);
        response.put("structuredFieldCount", structuredProfile.size());
        response.put("semanticMemories", semanticMemories);
        response.put("semanticMemoryCount", semanticMemories.size());
        response.put("conversationSummary", chatMemoryStore.getSummary(memoryId));
        response.put("shortTermMessageCount", chatMemoryStore.getMessageCount(memoryId));
        response.put("storageMode", chatMemoryStore.getStorageMode());
        response.put("redisAvailable", memoryManager.isRedisAvailable());
        response.put("longTermMemoryEnabled", memoryManager.isLongTermMemoryEnabled(userId));
        response.put("candidateCount", memoryManager.getSemanticMemoryCandidates(userId, 100).size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/candidates")
    public ResponseEntity<Map<String, Object>> getCandidates(
            @RequestParam(value = "maxResults", defaultValue = "50") int maxResults,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();
        List<Map<String, Object>> candidates = memoryManager.getSemanticMemoryCandidates(
                userId, Math.max(1, Math.min(maxResults, 100)));
        return ResponseEntity.ok(Map.of(
                "success", true,
                "count", candidates.size(),
                "candidates", candidates));
    }

    @PostMapping("/candidates/{memoryId}/confirm")
    public ResponseEntity<Map<String, Object>> confirmCandidate(
            @PathVariable String memoryId,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();
        boolean confirmed = memoryManager.confirmSemanticMemoryCandidate(userId, memoryId);
        return confirmed
                ? ResponseEntity.ok(Map.of("success", true, "memoryId", memoryId, "status", "ACTIVE"))
                : ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "memoryId", memoryId,
                        "message", "Candidate not found, long-term memory disabled, or confirmation failed."));
    }

    @DeleteMapping("/semantic/{memoryId}")
    public ResponseEntity<Map<String, Object>> deleteSemanticMemory(
            @PathVariable String memoryId,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();
        boolean deleted = memoryManager.deleteSemanticMemory(userId, memoryId);
        return deleted
                ? ResponseEntity.ok(Map.of("success", true, "memoryId", memoryId))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "longTermMemoryEnabled", memoryManager.isLongTermMemoryEnabled(userId)));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String userId = identityResolver.resolve(request).userId();
        Object value = body == null ? null : body.get("longTermMemoryEnabled");
        if (!(value instanceof Boolean enabled)) {
            return badRequest("longTermMemoryEnabled must be a boolean");
        }
        memoryManager.setLongTermMemoryEnabled(userId, enabled);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "longTermMemoryEnabled", enabled));
    }

    /**
     * 记忆召回端点 —— 综合三层记忆进行智能召回。
     *
     * <p>结合用户的结构化档案、语义记忆和对话历史，
     * 根据当前消息和路由决策，智能选择和组合相关的记忆信息。
     * 返回的 MemoryRecallRecord 可直接注入到 LLM 提示词中。</p>
     *
     * @param request 请求体，包含 userId、researchGroupId、conversationId、
     *                message 和可选的 routeDecision
     * @return 记忆召回记录，包含召回的记忆内容和元数据
     */
    @PostMapping("/recall")
    public ResponseEntity<MemoryRecallRecord> recall(@RequestBody Map<String, Object> request,
                                                     HttpServletRequest servletRequest) {
        // 解析请求参数，设置默认值
        RequestIdentity identity = identityResolver.resolve(servletRequest);
        String userId = identity.userId();
        String researchGroupId = identity.knowledgeSpaceId();
        String conversationId = stringValue(request, "conversationId", defaultConversationId(userId, null));
        String message = stringValue(request, "message", "");

        // 尝试提取路由决策信息（用于辅助记忆召回策略）
        HybridRouteDecision routeDecision = null;
        Object routeValue = request == null ? null : request.get("routeDecision");
        if (routeValue instanceof HybridRouteDecision decision) {
            routeDecision = decision;
        }

        return ResponseEntity.ok(memoryRecallService.recall(
                userId, researchGroupId, conversationId, message, routeDecision));
    }

    // ==================== 内部工具方法 ====================

    /**
     * 构建记忆 ID：由 userId 和 conversationId 组合而成。
     *
     * @param userId         用户 ID
     * @param conversationId 对话 ID（可选）
     * @return 格式为 "userId:conversationId" 的记忆 ID
     */
    private String buildMemoryId(String userId, String conversationId) {
        return userId + ":" + defaultConversationId(userId, conversationId);
    }

    /**
     * 获取默认对话 ID：未指定时使用 "userId:default" 格式。
     *
     * @param userId         用户 ID
     * @param conversationId 对话 ID（可选）
     * @return 有效的对话 ID
     */
    private String defaultConversationId(String userId, String conversationId) {
        return isBlank(conversationId) ? userId + ":default" : conversationId.trim();
    }

    /**
     * 构建 400 Bad Request 响应。
     *
     * @param message 错误信息
     * @return 包含 success=false 和错误信息的 ResponseEntity
     */
    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }

    /**
     * 从 Map 中安全提取字符串值。
     *
     * @param map 源 Map
     * @param key 键名
     * @return 字符串值或 null
     */
    private String value(Map<String, String> map, String key) {
        return map == null ? null : map.get(key);
    }

    /**
     * 从 Map 中安全提取字符串值，缺失时返回 fallback。
     *
     * @param map      源 Map
     * @param key      键名
     * @param fallback 默认值
     * @return 字符串值或 fallback
     */
    private String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    /**
     * 返回默认值：当 value 为空或空白时返回 fallback。
     *
     * @param value    原始值
     * @param fallback 默认值
     * @return 非空非空白的值
     */
    private String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    /**
     * 判断字符串是否为空或空白。
     *
     * @param value 待检查的字符串
     * @return true 表示为空或空白
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
