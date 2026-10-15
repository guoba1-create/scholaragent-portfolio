package com.team.rag.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 检索诊断结果
 *
 * <p>用于诊断向量检索的质量，检查命中数量、分数分布和潜在问题。
 * 帮助排查"检索不到结果"或"检索结果不相关"等问题。</p>
 *
 * @see com.team.rag.service.RetrievalDiagnosticsService — 诊断服务
 * @see RetrievalHit — 检索命中
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetrievalDiagnosticsResult {
    /** 诊断是否成功执行 */
    public boolean success = true;
    /** 用户 ID */
    public String userId;
    /** 研究组 ID */
    public String researchGroupId;
    /** 检索查询文本 */
    public String query;
    /** 请求的 topK 值 */
    public int topK;
    /** 最小分数阈值 */
    public double minScore;
    /** 研究组的文档 Manifest 数量 */
    public int manifestCount;
    /** 检索命中数量 */
    public int retrievalHitCount;
    /** 命中结果的分数列表（降序） */
    public List<Double> topScores = new ArrayList<>();
    /** 检索命中详情列表 */
    public List<RetrievalHit> hits = new ArrayList<>();
    /** 警告信息（如命中数为 0、分数过低等） */
    public List<String> warnings = new ArrayList<>();
    /** 修复建议（如上传文档、降低 minScore 等） */
    public List<String> suggestions = new ArrayList<>();
    /** 诊断执行时间 */
    public Instant createdAt = Instant.now();
}
