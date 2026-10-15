package com.team.rag.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档诊断结果
 *
 * <p>用于检查文档处理状态的完整性，对比 Manifest 记录与实际文件系统状态，
 * 发现缺失的页面和分块文件，并给出修复建议。</p>
 *
 * @see com.team.rag.service.DocumentDiagnosticsService — 诊断服务
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentDiagnosticsResult {
    /** 诊断是否成功执行 */
    public boolean success = true;
    /** 文档 ID */
    public String documentId;
    /** 实际生效的文档 ID（如果是重复文档，指向原始文档） */
    public String effectiveDocumentId;
    /** 研究组 ID */
    public String researchGroupId;
    /** 文件名 */
    public String filename;
    /** 文档状态（completed/processing/failed） */
    public String status;
    /** 使用的解析器名称 */
    public String parser;
    /** 文件系统存储路径 */
    public String storagePath;
    /** 是否为重复文档（指向原始文档） */
    public boolean duplicated;
    /** 原始 PDF 文件是否存在 */
    public boolean originalFileExists;
    /** 页面目录是否存在 */
    public boolean pagesDirectoryExists;
    /** 分块目录是否存在 */
    public boolean chunksDirectoryExists;
    /** Structure-layer JSON file exists. */
    public boolean structureFileExists;
    /** Manifest 记录的页面数 */
    public int manifestPageCount;
    /** Manifest 记录的分块数 */
    public int manifestChunkCount;
    /** 实际页面文件数 */
    public int actualPageFileCount;
    /** 实际分块文件数 */
    public int actualChunkFileCount;
    /** 缺失的页面编号列表 */
    public List<Integer> missingPages = new ArrayList<>();
    /** 缺失的分块编号列表 */
    public List<Integer> missingChunks = new ArrayList<>();
    /** 警告信息列表 */
    public List<String> warnings = new ArrayList<>();
    /** 修复建议列表 */
    public List<String> suggestions = new ArrayList<>();
    /** 诊断执行时间 */
    public Instant createdAt = Instant.now();
}
