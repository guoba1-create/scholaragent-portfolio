package com.team.rag.service;

import com.team.rag.agent.routing.HybridRouteDecision;
import com.team.rag.agent.runtime.MemoryRecallRecord;
import com.team.rag.config.RedisChatMemoryStore;
import com.team.rag.entity.DocumentManifest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆召回服务
 *
 * <p>在每次工作流执行前调用，负责从四个维度召回相关记忆和上下文：</p>
 * <ol>
 *   <li><b>短期召回</b>：从 Redis 读取对话摘要和消息数</li>
 *   <li><b>语义召回</b>：在向量库中搜索相关语义记忆（top 6）</li>
 *   <li><b>项目召回</b>：列出研究组的文档 Manifest 摘要</li>
 * </ol>
 *
 * <p><b>容错设计：</b>每一步都独立 try-catch，某一步失败不会阻塞后续步骤，
 * 而是记录诊断信息到 {@link MemoryRecallRecord#diagnostics}。</p>
 *
 * <p><b>输出：</b>组装好的 {@link MemoryRecallRecord} 传给 {@link ContextBudgetManager} 做裁剪。</p>
 *
 * @see ContextBudgetManager — 接收本服务的输出，做上下文裁剪
 * @see DualEngineMemoryManager — 语义记忆存储和检索
 */
@Service
public class MemoryRecallService {

    private final RedisChatMemoryStore chatMemoryStore;
    private final DualEngineMemoryManager memoryManager;
    private final DocumentManifestStore manifestStore;

    public MemoryRecallService(RedisChatMemoryStore chatMemoryStore,
                               DualEngineMemoryManager memoryManager,
                               DocumentManifestStore manifestStore) {
        this.chatMemoryStore = chatMemoryStore;
        this.memoryManager = memoryManager;
        this.manifestStore = manifestStore;
    }

    /**
     * 执行四步记忆召回
     *
     * @param userId           用户 ID
     * @param researchGroupId  研究组 ID
     * @param conversationId   对话 ID
     * @param message          用户当前消息
     * @param routeDecision    路由决策结果（用于判断是否需要查询改写）
     * @return 组装好的 MemoryRecallRecord，包含四个维度的召回结果和诊断信息
     */
    public MemoryRecallRecord recall(String userId, String researchGroupId, String conversationId,
                                     String message, HybridRouteDecision routeDecision) {
        MemoryRecallRecord record = new MemoryRecallRecord();
        record.recallId = "recall_" + UUID.randomUUID().toString().substring(0, 8);
        record.userId = userId;
        record.researchGroupId = researchGroupId;
        record.conversationId = conversationId;
        record.message = message;
        record.originalQuery = defaultString(message, "research memory");
        record.routeDecision = routeDecision;
        record.memoryId = buildMemoryId(userId, conversationId);

        recallShortTerm(record);
        prepareEffectiveQuery(record);
        recallSemantic(record);
        recallProject(record);
        return record;
    }

    /**
     * 第一步：短期记忆召回
     * <p>从 Redis 读取对话摘要（由 RedisChatMemoryStore 自动生成）和消息数量。
     * 如果都没有，记录诊断信息。</p>
     */
    private void recallShortTerm(MemoryRecallRecord record) {
        try {
            record.shortTermSummary = chatMemoryStore.getSummary(record.memoryId);
            long messageCount = chatMemoryStore.getMessageCount(record.memoryId);
            if ((record.shortTermSummary == null || record.shortTermSummary.isBlank()) && messageCount == 0) {
                record.diagnostics.add("No short-term conversation memory found for memoryId=" + record.memoryId + ".");
            }
        } catch (Exception e) {
            record.diagnostics.add("Short-term memory recall failed: " + e.getMessage());
        }
    }

    /**
     * Query rewrite belongs to the retrieval pipeline, not memory recall.
     * Keeping the original query here avoids duplicate LLM and Pinecone work.
     */
    private void prepareEffectiveQuery(MemoryRecallRecord record) {
        String fallbackQuery = defaultString(record.message, "research memory");
        record.effectiveQuery = fallbackQuery;
        record.queryRewriteReason = "original_query";
    }

    /**
     * 第三步：语义记忆召回
     * <p>在向量库中搜索与当前问题语义最相关的个人记忆（top 6）。
     * 搜索时会按 researchGroupId 和 conversationId 做重排序加分。</p>
     */
    private void recallSemantic(MemoryRecallRecord record) {
        try {
            record.semanticMemories = memoryManager.searchSemanticMemories(
                    record.userId,
                    record.researchGroupId,
                    record.conversationId,
                    defaultString(record.effectiveQuery, "research memory"),
                    6,
                    0.0);
            if (record.semanticMemories == null || record.semanticMemories.isEmpty()) {
                record.diagnostics.add("No semantic memories matched this message.");
            }
        } catch (Exception e) {
            record.diagnostics.add("Semantic memory recall failed: " + e.getMessage());
        }
    }

    /**
     * 第四步：项目上下文召回
     * <p>Only lists document manifests. Literature evidence is retrieved once by
     * the formal retrieval pipeline and stored in RetrievalContext.</p>
     */
    private void recallProject(MemoryRecallRecord record) {
        Map<String, Object> project = new LinkedHashMap<>();
        try {
            List<DocumentManifest> manifests = manifestStore.list(record.researchGroupId);
            project.put("manifestCount", manifests.size());
            project.put("manifests", manifests.stream().limit(8).map(this::manifestPreview).toList());
            if (manifests.isEmpty()) {
                record.diagnostics.add("No document manifests found for researchGroupId=" + record.researchGroupId + ".");
            }
        } catch (Exception e) {
            record.diagnostics.add("Document manifest recall failed: " + e.getMessage());
        }

        record.projectContext = project;
    }

    private Map<String, Object> manifestPreview(DocumentManifest manifest) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("documentId", manifest.documentId);
        map.put("filename", manifest.filename);
        map.put("status", manifest.status);
        map.put("pageCount", manifest.pageCount);
        map.put("chunkCount", manifest.chunkCount);
        map.put("duplicatedFrom", manifest.duplicatedFrom);
        return map;
    }

    private String buildMemoryId(String userId, String conversationId) {
        String safeUserId = defaultString(userId, "user-001");
        String safeConversationId = defaultString(conversationId, safeUserId + ":default");
        return safeUserId + ":" + safeConversationId;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
