package com.team.rag.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.rag.entity.DocumentChunkRef;
import com.team.rag.entity.DocumentManifest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档图书馆目录服务（Document Library Catalog Service）。
 *
 * <p>职责：为研究组提供文档库的目录视图，列出所有已上传的文档及其元数据。
 * 是"我的文档库"功能的核心服务。</p>
 *
 * <h3>设计思想：</h3>
 * <p>将文档清单（DocumentManifest）转换为用户友好的目录格式（CatalogResult），
 * 包含文档的基本信息、处理状态、分块数量等。
 * 支持可选的 chunk 详情展示（最多 12 个 chunk）。</p>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * DocumentLibraryController（文档图书馆入口）
 *     └── DocumentLibraryCatalogService（本类：目录查询）
 *         └── DocumentManifestStore（查询文档清单）
 * </pre>
 */
@Service
public class DocumentLibraryCatalogService {

    /** 文档清单存储，用于查询研究组的文档列表 */
    private final DocumentManifestStore manifestStore;

    public DocumentLibraryCatalogService(DocumentManifestStore manifestStore) {
        this.manifestStore = manifestStore;
    }

    /**
     * 列出研究组的文档目录。
     *
     * <p>返回格式化的文档列表，包含每个文档的基本信息和可选的 chunk 详情。</p>
     *
     * @param researchGroupId 研究组标识
     * @param limit 最大返回数量（默认 30，最大 100）
     * @param includeChunks 是否包含 chunk 详情（最多 12 个）
     * @return 目录结果
     */
    public CatalogResult list(String researchGroupId, Integer limit, boolean includeChunks) {
        CatalogResult result = new CatalogResult();
        result.researchGroupId = normalizeResearchGroupId(researchGroupId);
        result.limit = clampLimit(limit);
        result.includeChunks = includeChunks;

        // 检查 researchGroupId 是否为空
        if (result.researchGroupId == null || result.researchGroupId.isBlank()) {
            result.warnings.add("researchGroupId is empty, so the document library cannot be isolated.");
            result.summary = "No research group was provided.";
            return result;
        }

        // 查询研究组的所有文档清单
        List<DocumentManifest> manifests = manifestStore.list(result.researchGroupId);
        result.total = manifests.size();
        // 转换为目录格式（限制返回数量）
        for (DocumentManifest manifest : manifests.stream().limit(result.limit).toList()) {
            result.papers.add(toPaperItem(manifest, includeChunks));
        }

        // 生成摘要信息
        if (result.total == 0) {
            result.summary = "No uploaded document manifests were found for researchGroupId=" + result.researchGroupId + ".";
            result.warnings.add("Upload documents or bind the external user to the correct researchGroupId before asking library-list questions.");
        } else {
            result.summary = "Found " + result.total + " document(s) for researchGroupId=" + result.researchGroupId + ".";
        }
        return result;
    }

    /**
     * 将文档清单转换为目录项。
     *
     * @param manifest 文档清单
     * @param includeChunks 是否包含 chunk 详情
     * @return 目录项
     */
    private PaperItem toPaperItem(DocumentManifest manifest, boolean includeChunks) {
        PaperItem item = new PaperItem();
        item.documentId = manifest.documentId;
        item.documentHash = manifest.documentHash;
        item.filename = manifest.filename;
        item.title = manifest.title;
        item.status = manifest.status;
        item.parser = manifest.parser;
        item.pageCount = manifest.pageCount;
        item.chunkCount = manifest.chunkCount;
        item.fileSizeBytes = manifest.fileSizeBytes;
        item.duplicatedFrom = manifest.duplicatedFrom;
        item.createdAt = manifest.createdAt;
        item.updatedAt = manifest.updatedAt;

        // 可选：包含 chunk 详情（最多 12 个）
        if (includeChunks && manifest.chunkRefs != null) {
            for (DocumentChunkRef chunk : manifest.chunkRefs.stream().limit(12).toList()) {
                ChunkItem chunkItem = new ChunkItem();
                chunkItem.documentId = chunk.documentId;
                chunkItem.filename = chunk.filename;
                chunkItem.title = chunk.title;
                chunkItem.section = chunk.section;
                chunkItem.sectionPath = chunk.sectionPath;
                chunkItem.pageNumber = chunk.pageNumber;
                chunkItem.pageStart = chunk.pageStart;
                chunkItem.pageEnd = chunk.pageEnd;
                chunkItem.chunkIndex = chunk.chunkIndex;
                chunkItem.sourcePath = chunk.sourcePath;
                item.chunks.add(chunkItem);
            }
        }
        return item;
    }

    /**
     * 限制返回数量（默认 30，最大 100）。
     */
    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 30;
        }
        return Math.min(limit, 100);
    }

    /**
     * 标准化研究组标识（去除首尾空格）。
     */
    private String normalizeResearchGroupId(String researchGroupId) {
        return researchGroupId == null ? null : researchGroupId.trim();
    }

    /**
     * 目录查询结果（内部类）。
     *
     * <p>包含研究组标识、文档总数、限制数量、摘要信息、文档列表和警告信息。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CatalogResult {
        /** 研究组标识 */
        public String researchGroupId;
        /** 文档总数 */
        public int total;
        /** 返回数量限制 */
        public int limit;
        /** 是否包含 chunk 详情 */
        public boolean includeChunks;
        /** 摘要信息 */
        public String summary;
        /** 文档列表 */
        public List<PaperItem> papers = new ArrayList<>();
        /** 警告信息 */
        public List<String> warnings = new ArrayList<>();
    }

    /**
     * 文档目录项（内部类）。
     *
     * <p>包含文档的基本信息和可选的 chunk 详情。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PaperItem {
        /** 文档唯一标识 */
        public String documentId;
        /** 文档哈希值（用于去重） */
        public String documentHash;
        /** 文件名 */
        public String filename;
        /** Paper title extracted from parser. */
        public String title;
        /** 处理状态（如 COMPLETED、FAILED、DUPLICATED） */
        public String status;
        /** 解析器类型（如 academic-pdf） */
        public String parser;
        /** 页数 */
        public int pageCount;
        /** 分块数量 */
        public int chunkCount;
        /** 文件大小（字节） */
        public long fileSizeBytes;
        /** 重复来源文档 ID（DUPLICATED 状态时有值） */
        public String duplicatedFrom;
        /** 创建时间 */
        public Instant createdAt;
        /** 更新时间 */
        public Instant updatedAt;
        /** chunk 详情列表（可选，最多 12 个） */
        public List<ChunkItem> chunks = new ArrayList<>();
    }

    /**
     * chunk 详情项（内部类）。
     *
     * <p>包含 chunk 的基本信息，用于目录展示。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChunkItem {
        /** 文档唯一标识 */
        public String documentId;
        /** 文件名 */
        public String filename;
        /** chunk 标题 */
        public String title;
        /** 所属章节 */
        public String section;
        /** Full section path. */
        public String sectionPath;
        /** 页码 */
        public Integer pageNumber;
        public Integer pageStart;
        public Integer pageEnd;
        /** chunk 索引 */
        public Integer chunkIndex;
        /** 源文件路径 */
        public String sourcePath;
    }
}
