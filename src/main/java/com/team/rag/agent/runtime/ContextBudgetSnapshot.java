package com.team.rag.agent.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文预算快照 —— 记录一次工作流中 Prompt 各组成部分的字符用量。
 *
 * <p>在 LLM 调用前，系统将多个上下文片段（短期记忆、语义记忆、项目文档、诊断信息等）
 * 拼接为完整的 Prompt。此快照记录每个部分的字符数和总数，用于：
 * <ul>
 *   <li>监控上下文是否接近 Token 上限</li>
 *   <li>诊断哪些部分消耗了最多上下文空间</li>
 *   <li>支持上下文裁剪策略的调优</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContextBudgetSnapshot {

    /** 使用的执行引擎名称 */
    public String engine;

    /** Agent Profile 标识 */
    public String profile;

    /** Prompt 字符数上限（由模型上下文窗口决定） */
    public int maxPromptChars;

    /** 当前 Prompt 总字符数（所有部分之和） */
    public int totalPromptChars;

    /** 短期记忆（对话历史）占用的字符数 */
    public int shortTermChars;

    /** 语义记忆（向量检索结果）占用的字符数 */
    public int semanticChars;

    /** 项目文档（CLAUDE.md 等）占用的字符数 */
    public int projectChars;

    /** 诊断信息占用的字符数 */
    public int diagnosticsChars;

    /** 选中的语义记忆条目数 */
    public int selectedSemanticCount;

    /** 选中的文档清单条目数 */
    public int selectedManifestCount;

    /** 选中的检索命中条目数 */
    public int selectedHitCount;

    /** 是否发生了上下文截断（true = 某些部分被裁剪以适应上限） */
    public boolean truncated;

    /** 被截断的部分名称列表 */
    public List<String> truncatedSections = new ArrayList<>();

    /** 最终组装的 Prompt 上下文文本（调试用途） */
    public String promptContext;

    /** 诊断信息列表，记录预算分配和裁剪的详细日志 */
    public List<String> diagnostics = new ArrayList<>();

    /** 快照创建时间 */
    public Instant createdAt = Instant.now();
}
