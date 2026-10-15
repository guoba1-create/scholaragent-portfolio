package com.team.rag.service;

import com.team.rag.entity.DocumentManifest;
import com.team.rag.entity.RetrievalDiagnosticsResult;
import com.team.rag.entity.RetrievalHit;
import com.team.rag.telegram.TelegramResearchGroupBinding;
import com.team.rag.telegram.TelegramResearchGroupBindingStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索诊断服务（Retrieval Diagnostics Service）。
 *
 * <p>职责：诊断向量检索的健康状态，发现潜在问题并提供修复建议。
 * 是检索失败排查和运维监控的核心工具。</p>
 *
 * <h3>设计思想：</h3>
 * <p>采用"检查清单"模式，逐项检查检索链的各个环节：</p>
 * <ul>
 *   <li>研究组标识是否为空</li>
 *   <li>文档清单是否存在</li>
 *   <li>向量检索是否返回结果</li>
 *   <li>Telegram 用户绑定是否正确</li>
 * </ul>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * RetrievalDiagnosticsController（检索诊断入口）
 *     └── RetrievalDiagnosticsService（本类：诊断逻辑）
 *         ├── DocumentManifestStore（查询文档清单）
 *         ├── RetrievalFormattingService（执行检索）
 *         └── TelegramResearchGroupBindingStore（检查 Telegram 绑定）
 * </pre>
 */
@Service
public class RetrievalDiagnosticsService {

    /** 文档清单存储 */
    private final DocumentManifestStore manifestStore;

    /** 检索格式化服务（执行实际的向量检索） */
    private final RetrievalFormattingService retrievalFormattingService;

    /** Telegram 研究组绑定存储（可选，用于 Telegram 用户诊断） */
    private final ObjectProvider<TelegramResearchGroupBindingStore> telegramBindingStoreProvider;

    public RetrievalDiagnosticsService(
            DocumentManifestStore manifestStore,
            RetrievalFormattingService retrievalFormattingService,
            ObjectProvider<TelegramResearchGroupBindingStore> telegramBindingStoreProvider) {
        this.manifestStore = manifestStore;
        this.retrievalFormattingService = retrievalFormattingService;
        this.telegramBindingStoreProvider = telegramBindingStoreProvider;
    }

    /**
     * 诊断检索健康状态。
     *
     * <p>诊断流程：</p>
     * <ol>
     *   <li>检查研究组标识是否为空</li>
     *   <li>检查文档清单是否存在</li>
     *   <li>执行向量检索并检查结果</li>
     *   <li>检查 Telegram 用户绑定（如果适用）</li>
     * </ol>
     *
     * @param userId 用户标识
     * @param researchGroupId 研究组标识
     * @param query 查询文本
     * @param topK 最大返回数
     * @param minScore 最低相似度阈值
     * @return 诊断结果
     */
    public RetrievalDiagnosticsResult diagnose(String userId, String researchGroupId,
                                               String query, int topK, double minScore) {
        RetrievalDiagnosticsResult result = new RetrievalDiagnosticsResult();
        result.userId = userId;
        result.researchGroupId = researchGroupId;
        result.query = query;
        result.topK = Math.max(1, topK);
        result.minScore = Math.max(0.0, minScore);

        // 检查 1：研究组标识是否为空
        if (isBlank(researchGroupId)) {
            result.warnings.add("researchGroupId is empty, so document filtering cannot target a project library.");
            result.suggestions.add("Bind the user/channel to a researchGroupId or pass one explicitly.");
            return result;
        }

        // 检查 2：文档清单是否存在
        List<DocumentManifest> manifests = manifestStore.list(researchGroupId);
        result.manifestCount = manifests.size();
        if (manifests.isEmpty()) {
            result.warnings.add("No document manifest exists for researchGroupId=" + researchGroupId + ".");
            result.suggestions.add("Upload papers into this research group before asking document-grounded questions.");
        }

        // 检查 3：执行向量检索并检查结果
        try {
            result.hits = retrievalFormattingService.retrieveLiteratureHits(
                    defaultString(query, "document query"), researchGroupId, result.topK, result.minScore, false);
            result.retrievalHitCount = result.hits.size();
            result.topScores = result.hits.stream()
                    .map(hit -> hit.score == null ? 0.0 : hit.score)
                    .toList();
            if (result.retrievalHitCount == 0 && result.manifestCount > 0) {
                result.warnings.add("Vector retrieval returned zero hits for this query.");
                result.suggestions.add("Lower minScore, try a keyword from the paper title/abstract, or verify chunk metadata.");
            }
        } catch (Exception e) {
            result.success = false;
            result.warnings.add("Retrieval failed: " + e.getMessage());
            result.suggestions.add("Check vector database connectivity and embedding model configuration.");
        }

        // 检查 4：Telegram 用户绑定（如果适用）
        addTelegramBindingHints(result);
        return result;
    }

    /**
     * 添加 Telegram 用户绑定诊断提示。
     *
     * <p>检查 Telegram 用户是否正确绑定了研究组，
     * 避免因绑定问题导致检索不到文档。</p>
     */
    private void addTelegramBindingHints(RetrievalDiagnosticsResult result) {
        // 只检查 Telegram 用户
        if (result.userId == null || !result.userId.startsWith("telegram:user:")) {
            return;
        }
        Long telegramUserId = parseLastLong(result.userId);
        if (telegramUserId == null) {
            result.warnings.add("Telegram userId format is not parseable: " + result.userId);
            return;
        }
        TelegramResearchGroupBindingStore store = telegramBindingStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        TelegramResearchGroupBinding binding = store.getUserBinding(telegramUserId);
        if (binding == null) {
            result.warnings.add("Telegram user has no explicit researchGroup binding.");
            result.suggestions.add("Bind the Telegram user to the intended researchGroupId before retrieval.");
        } else if (binding.defaultResearchGroupId == null || binding.defaultResearchGroupId.isBlank()) {
            result.warnings.add("Telegram user binding exists but has no default researchGroupId.");
        } else if (!binding.defaultResearchGroupId.equals(result.researchGroupId)) {
            result.warnings.add("Telegram user default researchGroupId is "
                    + binding.defaultResearchGroupId + ", current request uses " + result.researchGroupId + ".");
        }
    }

    /**
     * 从字符串末尾解析 Long 值（如 "telegram:user:123456" → 123456）。
     */
    private Long parseLastLong(String value) {
        int index = value.lastIndexOf(':');
        String tail = index >= 0 ? value.substring(index + 1) : value;
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全的字符串默认值。
     */
    private String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    /**
     * 检查字符串是否为空。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
