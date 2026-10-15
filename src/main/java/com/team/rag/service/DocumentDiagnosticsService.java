package com.team.rag.service;

import com.team.rag.entity.DocumentDiagnosticsResult;
import com.team.rag.entity.DocumentManifest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文档诊断服务（Document Diagnostics Service）。
 *
 * <p>职责：检查文档的存储完整性，发现潜在问题并提供修复建议。
 * 是文档上传失败排查和运维监控的核心工具。</p>
 *
 * <h3>设计思想：</h3>
 * <p>采用"检查清单"模式，逐项检查文档的各个组成部分：</p>
 * <ul>
 *   <li>文档清单（Manifest）是否存在</li>
 *   <li>原始文件是否存在</li>
 *   <li>页面文件目录是否存在</li>
 *   <li>分块文件目录是否存在</li>
 *   <li>页面文件数量是否与清单一致</li>
 *   <li>分块文件数量是否与清单一致</li>
 * </ul>
 *
 * <p>每个检查项都会生成对应的警告和建议，帮助开发者快速定位问题。</p>
 *
 * <h3>在系统中的位置：</h3>
 * <pre>
 * DocumentDiagnosticsController（文档诊断入口）
 *     └── DocumentDiagnosticsService（本类：诊断逻辑）
 *         ├── DocumentManifestStore（查询文档清单）
 *         └── DocumentReadService（检查文件系统）
 * </pre>
 */
@Service
public class DocumentDiagnosticsService {

    /** 文档清单存储，用于查询文档元数据 */
    private final DocumentManifestStore manifestStore;

    /** 文档读取服务，用于检查文件系统 */
    private final DocumentReadService documentReadService;

    public DocumentDiagnosticsService(DocumentManifestStore manifestStore, DocumentReadService documentReadService) {
        this.manifestStore = manifestStore;
        this.documentReadService = documentReadService;
    }

    /**
     * 诊断文档存储完整性。
     *
     * <p>诊断流程：</p>
     * <ol>
     *   <li>检查文档清单是否存在</li>
     *   <li>处理重复文档（DUPLICATED 状态）</li>
     *   <li>检查原始文件是否存在</li>
     *   <li>检查页面文件目录是否存在</li>
     *   <li>检查分块文件目录是否存在</li>
     *   <li>检查页面文件数量是否与清单一致</li>
     *   <li>检查分块文件数量是否与清单一致</li>
     *   <li>生成警告和建议</li>
     * </ol>
     *
     * @param documentId 文档唯一标识
     * @return 诊断结果（包含警告、建议、缺失的文件等）
     */
    public DocumentDiagnosticsResult diagnose(String documentId) {
        DocumentDiagnosticsResult result = new DocumentDiagnosticsResult();
        result.documentId = documentId;

        // 检查 1：文档清单是否存在
        DocumentManifest manifest = manifestStore.get(documentId);
        if (manifest == null) {
            result.success = false;
            result.warnings.add("Document manifest was not found.");
            result.suggestions.add("Check whether the upload completed or the documentId is correct.");
            return result;
        }

        // 检查 2：是否为重复文档
        result.duplicated = "DUPLICATED".equalsIgnoreCase(manifest.status);
        if (result.duplicated && manifest.duplicatedFrom != null) {
            result.warnings.add("This manifest is duplicated from " + manifest.duplicatedFrom + ".");
            DocumentManifest source = manifestStore.get(manifest.duplicatedFrom);
            if (source != null) {
                manifest = source;  // 使用原始文档的清单进行后续检查
            } else {
                result.success = false;
                result.suggestions.add("The source manifest for this duplicate is missing.");
                return fillManifest(result, manifest);
            }
        }

        // 填充清单信息
        fillManifest(result, manifest);
        Path documentDir = documentReadService.documentDirectory(manifest);
        result.storagePath = documentDir.toString();
        Path originalDir = documentDir.resolve("original");
        Path pagesDir = documentDir.resolve("pages");
        Path chunksDir = documentDir.resolve("chunks");
        Path structurePath = documentReadService.structurePath(manifest);

        // 检查 3-5：目录和文件是否存在
        result.originalFileExists = existsAnyFile(originalDir);
        result.pagesDirectoryExists = Files.isDirectory(pagesDir);
        result.chunksDirectoryExists = Files.isDirectory(chunksDir);
        result.structureFileExists = Files.isRegularFile(structurePath);
        result.actualPageFileCount = countFiles(pagesDir, "page-");
        result.actualChunkFileCount = countFiles(chunksDir, "chunk-");

        // 生成警告
        if (!result.originalFileExists) {
            result.warnings.add("Original uploaded file is missing from local storage.");
            result.suggestions.add("Re-upload the document or verify the document storage path.");
        }
        if (!result.structureFileExists) {
            if (!result.pagesDirectoryExists) {
                result.warnings.add("Pages directory is missing.");
            }
            if (!result.chunksDirectoryExists) {
                result.warnings.add("Chunks directory is missing.");
            }
        } else if (!result.chunksDirectoryExists) {
            result.suggestions.add("Document uses structure.json as the source layer; per-chunk JSON files are no longer required.");
        }
        // 检查 6：页面文件数量是否与清单一致
        if (result.manifestPageCount > 0 && result.actualPageFileCount < result.manifestPageCount) {
            result.warnings.add("Stored page files are fewer than the manifest pageCount.");
            result.missingPages = missingIndexes(pagesDir, result.manifestPageCount, true);
        }
        // 检查 7：分块文件数量是否与清单一致
        if (!result.structureFileExists && result.manifestChunkCount > 0 && result.actualChunkFileCount < result.manifestChunkCount) {
            result.warnings.add("Stored chunk files are fewer than the manifest chunkCount.");
            result.missingChunks = missingIndexes(chunksDir, result.manifestChunkCount, false);
        }
        // 检查 8：处理状态是否为失败
        if ("FAILED".equalsIgnoreCase(result.status)) {
            result.suggestions.add("Inspect the upload task status and manifest errorMessage before retrying.");
        }
        // 无警告时给出肯定性建议
        if (result.warnings.isEmpty()) {
            result.suggestions.add("Document storage looks consistent with the manifest.");
        }
        return result;
    }

    /**
     * 从清单中填充诊断结果的基本信息。
     */
    private DocumentDiagnosticsResult fillManifest(DocumentDiagnosticsResult result, DocumentManifest manifest) {
        result.effectiveDocumentId = manifest.documentId;
        result.researchGroupId = manifest.researchGroupId;
        result.filename = manifest.filename;
        result.status = manifest.status;
        result.parser = manifest.parser;
        result.manifestPageCount = manifest.pageCount;
        result.manifestChunkCount = manifest.chunkCount;
        result.storagePath = manifest.storagePath;
        return result;
    }

    /**
     * 检查目录中是否存在任何文件。
     */
    private boolean existsAnyFile(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 统计目录中指定前缀的文件数量。
     *
     * @param directory 目录路径
     * @param prefix 文件名前缀（如 "page-"、"chunk-"）
     * @return 文件数量
     */
    private int countFiles(Path directory, String prefix) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 查找缺失的文件索引。
     *
     * <p>例如：如果期望有 10 个页面文件（page-0001.txt ~ page-0010.txt），
     * 但实际只有 8 个，则返回缺失的 2 个索引。</p>
     *
     * <p>最多返回 8 个缺失索引，避免输出过长。</p>
     */
    private List<Integer> missingIndexes(Path directory, int expectedCount, boolean pageMode) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        String prefix = pageMode ? "page-" : "chunk-";
        String suffix = pageMode ? ".txt" : ".json";
        int start = pageMode ? 1 : 0;  // 页面从 1 开始，chunk 从 0 开始
        return Stream.iterate(start, i -> i + 1)
                .limit(expectedCount)
                .filter(index -> !Files.exists(directory.resolve(prefix + String.format("%04d", index) + suffix)))
                .limit(8)  // 最多返回 8 个缺失索引
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
