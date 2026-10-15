package com.team.rag.agent.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.rag.agent.routing.HybridRouteDecision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆召回记录 —— 记忆系统单次召回过程的完整审计轨迹。
 *
 * <p>职责：在四层记忆系统（短期记忆、语义记忆、项目上下文、Manifest）执行召回时，
 * 记录从查询改写到最终结果的完整过程，包括各层命中情况、诊断信息和性能指标。
 *
 * <p>设计思想：
 * <ul>
 *   <li>使用 {@code @JsonInclude(NON_NULL)} 序列化时忽略空字段，减少存储和传输开销</li>
 *   <li>记录查询改写的前后对比（originalQuery vs effectiveQuery vs rewrittenQuery），
 *       便于分析改写策略的效果</li>
 *   <li>保留路由决策（routeDecision），用于分析意图分类对记忆召回的影响</li>
 *   <li>{@code diagnostics} 列表记录召回过程中的异常和警告，便于运维排查</li>
 *   <li>各层命中计数（selectedSemanticCount 等）用于统计记忆层的使用率</li>
 * </ul>
 *
 * <p>在系统中的位置：MemoryService.recall() -> 各层检索 -> 生成 MemoryRecallRecord
 * -> 存入 Trace 或返回给调用方用于调试。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryRecallRecord {

    /** 本次召回的唯一标识 */
    public String recallId;

    /** 用户标识 */
    public String userId;

    /** 研究组标识 */
    public String researchGroupId;

    /** 会话标识 */
    public String conversationId;

    /** 用户原始消息文本 */
    public String message;

    /** 用户输入的原始查询文本 */
    public String originalQuery;

    /** 经过预处理后的有效查询（可能包含上下文拼接） */
    public String effectiveQuery;

    /** 经过查询改写后的最终查询（如 HyDE、QueryExpansion 等） */
    public String rewrittenQuery;

    /** 是否应用了查询改写 */
    public boolean queryRewriteApplied;

    /** 查询改写的原因说明 */
    public String queryRewriteReason;

    /** 混合路由决策，记录意图分类结果 */
    public HybridRouteDecision routeDecision;

    /** 记忆条目的标识 */
    public String memoryId;

    /** 短期记忆摘要（最近对话的压缩摘要） */
    public String shortTermSummary;

    /** 语义记忆条目列表，每个元素为一条语义记忆的键值对表示 */
    public List<Map<String, Object>> semanticMemories = new ArrayList<>();

    /** 项目上下文信息（如研究组配置、用户偏好等） */
    public Map<String, Object> projectContext;

    /** 诊断信息列表，记录召回过程中的异常、警告和调试信息 */
    public List<String> diagnostics = new ArrayList<>();

    /** 压缩后的提示上下文文本，最终注入到 LLM Prompt 中 */
    public String compactPromptContext;

    /** 被截断的段落标识列表，用于分析上下文窗口溢出情况 */
    public List<String> truncatedSections = new ArrayList<>();

    /** 被选中的语义记忆条数 */
    public int selectedSemanticCount;

    /** 被选中的 Manifest 条数 */
    public int selectedManifestCount;

    /** 被选中的命中记录条数 */
    public int selectedHitCount;

    /** 记录创建时间 */
    public Instant createdAt = Instant.now();
}
