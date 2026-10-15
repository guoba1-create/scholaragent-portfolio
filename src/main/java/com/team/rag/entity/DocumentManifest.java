package com.team.rag.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档清单实体 —— 记录一份已上传文档的元数据信息。
 *
 * <p>在系统中承担以下职责：
 * <ul>
 *   <li>作为文档的全局唯一索引，通过 documentId 定位文档</li>
 *   <li>通过 documentHash 实现文档去重检测</li>
 *   <li>跟踪文档解析状态（PENDING / PARSING / COMPLETED / FAILED）</li>
 *   <li>维护文档与其所有 chunk 引用（{@link DocumentChunkRef}）之间的映射关系</li>
 * </ul>
 *
 * <p>典型使用场景：文档上传后写入清单，检索时通过清单获取 chunk 引用列表。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentManifest {

    /** 文档全局唯一标识符，格式通常为 UUID */
    public String documentId;

    /** 文档内容的哈希值（如 SHA-256），用于去重检测 */
    public String documentHash;

    /** 原始文件名（含扩展名），如 "DeepLearning.pdf" */
    public String filename;

    /** Paper title extracted from the parser. Falls back to filename when unavailable. */
    public String title;

    /** Paper authors extracted by GROBID or another academic parser. */
    public List<String> authors = new ArrayList<>();

    /** 所属研究组标识，用于多租户隔离 */
    public String researchGroupId;

    /** 原始文件大小，单位为字节（bytes） */
    public long fileSizeBytes;

    /** 文档被拆分后的 chunk 总数 */
    public int chunkCount;

    /** 文档原始文件在对象存储中的路径 */
    public String storagePath;

    /** Path of the structure-layer JSON file. */
    public String structurePath;

    /** Structure schema version. */
    public String structureVersion;

    /** 文档总页数（PDF 等分页文档适用，非分页文档为 0） */
    public int pageCount;

    /** 文档下所有 chunk 的引用列表，每个元素指向一个 {@link DocumentChunkRef} */
    public List<DocumentChunkRef> chunkRefs = new ArrayList<>();

    /** 若该文档与已有文档重复，此字段记录来源文档的 documentId；否则为 null */
    public String duplicatedFrom;

    /** 文档解析器标识，如 "pdf-parser"、"docx-parser" 等 */
    public String parser;

    /** 章节检测方法："outline"（PDF 书签）、"regex"（正则匹配）、"page_fallback"（逐页回退）、"none"（未检测） */
    public String sectionDetectionMethod;

    /** 检测到的章节数（未检测时为 0） */
    public int sectionCount;

    /** Whether references/bibliography were excluded from indexing. */
    public boolean referencesRemoved;

    /** 文档当前状态，取值：PENDING（待解析）、PARSING（解析中）、COMPLETED（已完成）、FAILED（失败） */
    public String status;

    /** 解析失败时的错误信息，成功时为 null */
    public String errorMessage;

    /** 文档清单创建时间 */
    public Instant createdAt = Instant.now();

    /** 文档清单最近一次更新时间 */
    public Instant updatedAt = Instant.now();
}
