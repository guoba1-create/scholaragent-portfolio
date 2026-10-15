package com.team.rag.entity;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

/**
 * Rerank 结果实体 —— 表示经过重排序后的单条检索结果。
 *
 * <p>在 RAG 检索流程中，初始检索（向量 / 稀疏）返回的结果经过 Rerank 模型
 * 重新打分后，生成此对象。RerankScore 通常比初始 vectorScore 更准确地
 * 反映查询与文档片段的相关性。
 *
 * <p>支持两种数据来源：
 * <ul>
 *   <li>{@link EmbeddingMatch} —— LangChain4j 原始检索匹配结果</li>
 *   <li>{@link RetrievalHit} —— 系统内部检索命中结果</li>
 * </ul>
 */
public class RerankResult {

    /** 原始向量检索匹配结果（LangChain4j），当数据来源为向量检索时使用 */
    private EmbeddingMatch<TextSegment> originalMatch;

    /** 系统内部检索命中结果，当数据来源为自定义检索流程时使用 */
    private RetrievalHit retrievalHit;

    /** Rerank 模型给出的重排序得分，值越高表示与查询越相关 */
    private double rerankScore;

    /** Rerank 判定原因，解释为何给出该重排序分数 */
    private String reason;

    /** 原始向量检索得分，从 originalMatch 或 retrievalHit 中提取，用于对比分析 */
    private double vectorScore;

    public RerankResult() {
    }

    public RerankResult(EmbeddingMatch<TextSegment> originalMatch, double rerankScore, String reason) {
        this.originalMatch = originalMatch;
        this.rerankScore = rerankScore;
        this.reason = reason;
        this.vectorScore = originalMatch != null ? originalMatch.score() : 0.0;
    }

    public RerankResult(RetrievalHit retrievalHit, double rerankScore, String reason) {
        this.retrievalHit = retrievalHit;
        this.rerankScore = rerankScore;
        this.reason = reason;
        this.vectorScore = retrievalHit != null && retrievalHit.vectorScore != null
                ? retrievalHit.vectorScore
                : 0.0;
    }

    public EmbeddingMatch<TextSegment> getOriginalMatch() {
        return originalMatch;
    }

    public void setOriginalMatch(EmbeddingMatch<TextSegment> originalMatch) {
        this.originalMatch = originalMatch;
    }

    public RetrievalHit getRetrievalHit() {
        return retrievalHit;
    }

    public void setRetrievalHit(RetrievalHit retrievalHit) {
        this.retrievalHit = retrievalHit;
    }

    public double getRerankScore() {
        return rerankScore;
    }

    public void setRerankScore(double rerankScore) {
        this.rerankScore = rerankScore;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public double getVectorScore() {
        return vectorScore;
    }

    public void setVectorScore(double vectorScore) {
        this.vectorScore = vectorScore;
    }

    /**
     * 获取原始文本片段对象，从 originalMatch 中提取。
     *
     * @return 文本片段，若 originalMatch 为 null 则返回 null
     */
    public TextSegment getSegment() {
        return originalMatch != null ? originalMatch.embedded() : null;
    }

    /**
     * 获取命中内容的文本。优先从 retrievalHit 取值，其次从 originalMatch 的嵌入片段取值。
     *
     * @return 文本内容，无数据时返回空字符串
     */
    public String getContent() {
        if (retrievalHit != null && retrievalHit.content != null) {
            return retrievalHit.content;
        }
        TextSegment segment = getSegment();
        return segment != null ? segment.text() : "";
    }

    /**
     * 获取来源标识。优先从 retrievalHit 的 source / title 字段取值，
     * 其次从 originalMatch 的 metadata 中提取 "source"，兜底返回 "unknown"。
     *
     * @return 来源标识字符串
     */
    public String getSource() {
        if (retrievalHit != null) {
            if (retrievalHit.source != null && !retrievalHit.source.isBlank()) {
                return retrievalHit.source;
            }
            if (retrievalHit.title != null && !retrievalHit.title.isBlank()) {
                return retrievalHit.title;
            }
        }
        TextSegment segment = getSegment();
        if (segment != null && segment.metadata() != null) {
            return segment.metadata().getString("source");
        }
        return "unknown";
    }

    @Override
    public String toString() {
        return "RerankResult{" +
                "rerankScore=" + rerankScore +
                ", vectorScore=" + vectorScore +
                ", reason='" + reason + '\'' +
                ", source='" + getSource() + '\'' +
                '}';
    }
}
