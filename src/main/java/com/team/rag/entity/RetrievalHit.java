package com.team.rag.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索命中结果实体 —— 表示向量检索 / 稀疏检索返回的单条命中记录。
 *
 * <p>在 RAG 检索流程中，每次检索操作可能返回多个 RetrievalHit，
 * 经过 Rerank 排序后选出最优结果用于生成回答。
 *
 * <p>同时支持混合检索评分：vectorScore（向量相似度）和 sparseScore（稀疏关键词匹配度）
 * 可用于后续加权融合或 Rerank 输入。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetrievalHit {

    /** chunk 的文本内容，即检索命中的原始文本片段 */
    public String content;

    /** 来源标识，通常是文档文件名或 URL */
    public String source;

    /** 文档标题，用于引用展示 */
    public String title;

    /** 文档章节名称，如 "3.2 实验结果" */
    public String section;

    /** Full section path in the structure tree. */
    public String sectionPath;

    /** 所属文档的 documentId，关联 {@link DocumentManifest} */
    public String documentId;

    /** Stable chunk id in the document structure layer. */
    public String chunkId;

    /** Stable section id in the document structure layer. */
    public String sectionId;

    /** 所属文档的内容哈希，用于快速匹配去重 */
    public String documentHash;

    /** 文档原始来源路径（如本地文件路径或远程 URL） */
    public String sourcePath;

    /** chunk 所在页码（PDF 等分页文档适用） */
    public Integer pageNumber;

    /** First page covered by the chunk, when known. */
    public Integer pageStart;

    /** Last page covered by the chunk, when known. */
    public Integer pageEnd;

    /** chunk 在文档中的序号索引（从 0 开始） */
    public Integer chunkIndex;

    /** 综合排序得分，由 Rerank 或默认排序策略计算得出 */
    public Double score;

    /** 向量检索得分，表示查询与 chunk 的语义相似度（余弦相似度等） */
    public Double vectorScore;

    /** 稀疏向量检索得分，用于与稠密向量结果做 hybrid 融合 */
    public Double sparseScore;

    /** 命中原因说明，可选字段，用于调试或解释排序依据 */
    public String reason;

    /** 向量数据库中的 embedding 记录 ID，可用于溯源 */
    public String embeddingId;

    /** 扩展元数据键值对，存储检索过程中的附加信息（如过滤条件、分面信息等） */
    public Map<String, Object> metadata = new LinkedHashMap<>();
}
