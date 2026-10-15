package com.team.rag.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 文档 chunk 引用实体 —— 表示文档中某个文本片段（chunk）的定位信息。
 *
 * <p>作为 {@link DocumentManifest} 的子结构，记录每个 chunk 在文档中的
 * 精确位置（页码、序号）以及所属文档的基本信息。在检索命中后用于
 * 生成引用溯源链接，告知用户答案来自哪份文档的哪个位置。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentChunkRef {

    /** 所属文档的 documentId，关联 {@link DocumentManifest} */
    public String documentId;

    /** 所属研究组标识 */
    public String researchGroupId;

    /** 文档文件名（含扩展名） */
    public String filename;

    /** 文档标题，用于引用展示；构造时默认取 filename */
    public String title;

    /** chunk 所在章节描述，如 "page 3" 或 "document"（非分页文档） */
    public String section;

    /** Stable chunk id used by the document structure layer and Pinecone metadata. */
    public String chunkId;

    /** Stable section id from the document structure layer. */
    public String sectionId;

    /** Full section path, for example "3 Method > 3.2 Model Architecture". */
    public String sectionPath;

    /** chunk 所在页码（PDF 等分页文档适用，非分页文档为 null） */
    public Integer pageNumber;

    /** First page covered by this chunk, when known. */
    public Integer pageStart;

    /** Last page covered by this chunk, when known. */
    public Integer pageEnd;

    /** chunk 在文档中的序号索引（从 0 开始） */
    public Integer chunkIndex;

    /** Chunk order inside its section. */
    public Integer indexInSection;

    /** Adjacent chunk in the same section. */
    public String prevChunkId;

    /** Adjacent chunk in the same section. */
    public String nextChunkId;

    /** 该 chunk 的检索相关性评分（可选，检索时填入） */
    public Double score;

    /** 文档原始来源路径 */
    public String sourcePath;

    /** 文档在对象存储中的路径 */
    public String storagePath;

    /** 无参构造函数 */
    public DocumentChunkRef() {
    }

    /**
     * 全参构造函数，自动推导 title 和 section 默认值。
     *
     * @param documentId    所属文档 ID
     * @param researchGroupId 所属研究组 ID
     * @param filename      文档文件名
     * @param pageNumber    页码（可为 null）
     * @param chunkIndex    chunk 序号索引
     */
    public DocumentChunkRef(String documentId, String researchGroupId, String filename,
                            Integer pageNumber, Integer chunkIndex) {
        this.documentId = documentId;
        this.researchGroupId = researchGroupId;
        this.filename = filename;
        this.title = filename;
        this.section = pageNumber == null ? "document" : "page " + pageNumber;
        this.pageNumber = pageNumber;
        this.pageStart = pageNumber;
        this.pageEnd = pageNumber;
        this.chunkIndex = chunkIndex;
    }
}
