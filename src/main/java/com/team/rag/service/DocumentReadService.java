package com.team.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.entity.DocumentChunkRef;
import com.team.rag.entity.DocumentManifest;
import com.team.rag.entity.DocumentReadResult;
import com.team.rag.entity.DocumentStructure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the local document structure layer.
 *
 * <p>The current source of truth is
 * {@code data/documents/{space}/{documentId}/structure.json}, produced from
 * GROBID sections (PDFBox/Tika are fallbacks), plus the immutable original
 * upload. Chunk and page endpoints resolve against {@code structure.json}.
 * Legacy {@code pages/} and {@code chunks/} files remain read-only fallbacks
 * so existing uploads stay compatible; new ingestion does not require them.</p>
 */
@Service
public class DocumentReadService {

    /** 文档存储根目录（绝对路径），默认为 data/documents */
    private final Path storageRoot;

    /** 文档清单存储，用于查询文档元数据（如研究组、文件名等） */
    private final DocumentManifestStore manifestStore;

    /** JSON 序列化器，用于读写 chunk JSON 文件 */
    private final ObjectMapper objectMapper;

    public DocumentReadService(
            @Value("${team-rag.document-storage-path:data/documents}") String storageRoot,
            DocumentManifestStore manifestStore,
            ObjectMapper objectMapper) {
        // 转换为绝对路径并规范化，避免路径遍历攻击
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.manifestStore = manifestStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取文档存储根目录。
     *
     * @return 根目录绝对路径
     */
    public Path root() {
        return storageRoot;
    }

    /**
     * 根据研究组和文档 ID 计算文档目录路径。
     *
     * @param researchGroupId 研究组标识
     * @param documentId 文档唯一标识
     * @return 文档目录的绝对路径
     */
    public Path documentDirectory(String researchGroupId, String documentId) {
        // safeFolder() 将研究组 ID 转换为安全的目录名（去除特殊字符）
        return storageRoot.resolve(safeFolder(researchGroupId)).resolve(documentId).normalize();
    }

    /**
     * 根据文档清单获取文档目录路径。
     *
     * <p>优先使用 manifest 中的 storagePath（如果有），
     * 否则根据 researchGroupId 和 documentId 计算路径。</p>
     *
     * @param manifest 文档清单
     * @return 文档目录的绝对路径
     */
    public Path documentDirectory(DocumentManifest manifest) {
        if (manifest == null) {
            return storageRoot;
        }
        // 优先使用 manifest 中记录的存储路径（兼容旧版数据）
        if (manifest.storagePath != null && !manifest.storagePath.isBlank()) {
            return Path.of(manifest.storagePath).toAbsolutePath().normalize();
        }
        return documentDirectory(manifest.researchGroupId, manifest.documentId);
    }

    public Path structurePath(DocumentManifest manifest) {
        if (manifest != null && manifest.structurePath != null && !manifest.structurePath.isBlank()) {
            return Path.of(manifest.structurePath).toAbsolutePath().normalize();
        }
        return documentDirectory(manifest).resolve("structure.json");
    }

    public void writeStructure(Path documentDir, DocumentStructure structure) throws IOException {
        writeJson(documentDir.resolve("structure.json"), structure);
    }

    public DocumentStructure readStructure(DocumentManifest manifest) throws IOException {
        Path path = structurePath(manifest);
        if (!Files.exists(path)) {
            return null;
        }
        return objectMapper.readValue(path.toFile(), DocumentStructure.class);
    }

    /**
     * 按页码范围读取文档原文。
     *
     * <p>这是文档引用溯源的核心方法——当 RAG 系统需要展示"这句话来自第几页"时，
     * 就是通过这个方法读取原始页面内容。</p>
     *
     * <p>处理逻辑：</p>
     * <ol>
     *   <li>查询文档清单，获取文档元数据</li>
     *   <li>处理"重复文档"（DUPLICATED 状态，指向原始文档）</li>
     *   <li>校正页码范围（1-based，不超过文档总页数）</li>
     *   <li>逐页读取文本文件，拼接为完整内容</li>
     * </ol>
     *
     * @param documentId 文档唯一标识
     * @param pageStart 起始页码（1-based，可选，默认为 1）
     * @param pageEnd 结束页码（1-based，可选，默认为 pageStart）
     * @return 读取结果，包含拼接后的文本和页面引用信息
     */
    public DocumentReadResult readPages(String documentId, Integer pageStart, Integer pageEnd) {
        // 查询文档清单
        DocumentManifest manifest = manifestStore.get(documentId);
        if (manifest == null) {
            return DocumentReadResult.failure(documentId, "Document manifest not found.");
        }
        // 处理"重复文档"：DUPLICATED 状态的文档指向原始文档
        // 这样重复上传的文档不会占用额外的存储空间
        if ("DUPLICATED".equalsIgnoreCase(manifest.status) && manifest.duplicatedFrom != null) {
            manifest = manifestStore.get(manifest.duplicatedFrom);
            if (manifest == null) {
                return DocumentReadResult.failure(documentId, "Duplicated source document not found.");
            }
        }

        // 校正页码范围：确保在有效范围内
        int start = Math.max(1, pageStart == null ? 1 : pageStart);
        int end = Math.max(start, pageEnd == null ? start : pageEnd);
        if (manifest.pageCount > 0) {
            end = Math.min(end, manifest.pageCount);
        }

        // 逐页读取文本文件
        Path pagesDir = documentDirectory(manifest).resolve("pages");
        if (!Files.isDirectory(pagesDir)) {
            DocumentReadResult structured = readPagesFromStructure(manifest, start, end);
            if (structured != null) {
                return structured;
            }
        }
        StringBuilder text = new StringBuilder();
        List<DocumentChunkRef> refs = new ArrayList<>();
        try {
            for (int page = start; page <= end; page++) {
                Path pageFile = pagesDir.resolve(pageFileName(page));
                if (!Files.exists(pageFile)) {
                    continue;  // 跳过不存在的页面（可能是空白页）
                }
                if (!text.isEmpty()) {
                    text.append("\n\n");  // 页面之间用空行分隔
                }
                // 添加页码标记，方便引用溯源
                text.append("[Page ").append(page).append("]\n");
                text.append(Files.readString(pageFile, StandardCharsets.UTF_8));
                // 记录页面引用信息，用于后续的引用溯源
                DocumentChunkRef ref = new DocumentChunkRef(
                        manifest.documentId, manifest.researchGroupId, manifest.filename, page, null);
                ref.storagePath = pageFile.toString();
                refs.add(ref);
            }
        } catch (IOException e) {
            return DocumentReadResult.failure(documentId, e.getMessage());
        }

        // 组装返回结果
        DocumentReadResult result = baseResult(manifest);
        result.success = true;
        result.pageStart = start;
        result.pageEnd = end;
        result.text = text.toString();
        result.chunkRefs = refs;
        return result;
    }

    /**
     * 按块索引读取文档分块。
     *
     * <p>每个 chunk 是一个 JSON 文件，包含：</p>
     * <ul>
     *   <li>text - 分块的文本内容</li>
     *   <li>metadata - 元数据（标题、章节、页码等）</li>
     * </ul>
     *
     * <p>这个方法主要用于文档诊断和调试——当需要查看某个 chunk 的具体内容时使用。</p>
     *
     * @param documentId 文档唯一标识
     * @param chunkIndex 块索引（0-based）
     * @return 读取结果，包含 chunk 的文本内容和元数据
     */
    public DocumentReadResult readChunk(String documentId, int chunkIndex) {
        // 查询文档清单
        DocumentManifest manifest = manifestStore.get(documentId);
        if (manifest == null) {
            return DocumentReadResult.failure(documentId, "Document manifest not found.");
        }
        // 处理"重复文档"
        if ("DUPLICATED".equalsIgnoreCase(manifest.status) && manifest.duplicatedFrom != null) {
            manifest = manifestStore.get(manifest.duplicatedFrom);
            if (manifest == null) {
                return DocumentReadResult.failure(documentId, "Duplicated source document not found.");
            }
        }

        // 读取 chunk JSON 文件
        DocumentReadResult structured = readChunkFromStructure(manifest, chunkIndex);
        if (structured != null) {
            return structured;
        }

        Path chunkFile = documentDirectory(manifest).resolve("chunks").resolve(chunkFileName(chunkIndex));
        if (!Files.exists(chunkFile)) {
            return DocumentReadResult.failure(documentId, "Document chunk not found: " + chunkIndex);
        }
        try {
            // 解析 JSON 为 Map（保持插入顺序）
            Map<String, Object> chunk = objectMapper.readValue(
                    Files.readString(chunkFile, StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            // 组装返回结果
            DocumentReadResult result = baseResult(manifest);
            result.success = true;
            result.chunkIndex = chunkIndex;
            result.chunk = chunk;
            Object text = chunk.get("text");
            result.text = text == null ? "" : text.toString();
            // 生成 chunk 引用信息，用于引用溯源
            result.chunkRefs = List.of(toRef(manifest, chunk, chunkIndex, chunkFile));
            return result;
        } catch (IOException e) {
            return DocumentReadResult.failure(documentId, e.getMessage());
        }
    }

    /**
     * 写入文本文件。
     *
     * <p>自动创建父目录，UTF-8 编码。</p>
     *
     * @param path 文件路径
     * @param text 文本内容（null 时写入空字符串）
     * @throws IOException IO 异常
     */
    private DocumentReadResult readPagesFromStructure(DocumentManifest manifest, int pageStart, int pageEnd) {
        try {
            DocumentStructure structure = readStructure(manifest);
            if (structure == null || structure.chunks == null || structure.chunks.isEmpty()) {
                return null;
            }
            boolean fullDocumentRequested = pageStart <= 1 && (manifest.pageCount <= 0 || pageEnd >= manifest.pageCount);
            StringBuilder text = new StringBuilder();
            List<DocumentChunkRef> refs = new ArrayList<>();
            for (DocumentStructure.DocumentChunk chunk : structure.chunks) {
                if (!fullDocumentRequested && !overlaps(chunk.pageStart, chunk.pageEnd, pageStart, pageEnd)) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append("[")
                        .append(chunk.sectionPath == null || chunk.sectionPath.isBlank() ? "Document" : chunk.sectionPath)
                        .append(" / Chunk ")
                        .append(chunk.chunkIndex)
                        .append("]\n")
                        .append(chunk.text == null ? "" : chunk.text);
                refs.add(toRef(manifest, chunk));
            }
            if (text.isEmpty()) {
                return null;
            }
            DocumentReadResult result = baseResult(manifest);
            result.success = true;
            result.pageStart = pageStart;
            result.pageEnd = pageEnd;
            result.text = text.toString();
            result.chunkRefs = refs;
            result.structurePath = structurePath(manifest).toString();
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    private DocumentReadResult readChunkFromStructure(DocumentManifest manifest, int chunkIndex) {
        try {
            DocumentStructure structure = readStructure(manifest);
            if (structure == null || structure.chunks == null) {
                return null;
            }
            DocumentStructure.DocumentChunk chunk = structure.chunks.stream()
                    .filter(item -> item.chunkIndex == chunkIndex)
                    .findFirst()
                    .orElse(null);
            if (chunk == null) {
                return null;
            }
            DocumentReadResult result = baseResult(manifest);
            result.success = true;
            result.chunkIndex = chunkIndex;
            result.pageStart = chunk.pageStart;
            result.pageEnd = chunk.pageEnd;
            result.text = chunk.text == null ? "" : chunk.text;
            result.chunk = objectMapper.convertValue(chunk, new TypeReference<LinkedHashMap<String, Object>>() {});
            DocumentStructure.DocumentSection section = findSection(structure, chunk.sectionId);
            if (section != null) {
                result.section = objectMapper.convertValue(section, new TypeReference<LinkedHashMap<String, Object>>() {});
            }
            result.chunkRefs = List.of(toRef(manifest, chunk));
            result.structurePath = structurePath(manifest).toString();
            return result;
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    private DocumentStructure.DocumentSection findSection(DocumentStructure structure, String sectionId) {
        if (sectionId == null || structure.sections == null) {
            return null;
        }
        return structure.sections.stream()
                .filter(section -> sectionId.equals(section.sectionId))
                .findFirst()
                .orElse(null);
    }

    private boolean overlaps(Integer chunkStart, Integer chunkEnd, int requestedStart, int requestedEnd) {
        if (chunkStart == null && chunkEnd == null) {
            return true;
        }
        int start = chunkStart == null ? chunkEnd : chunkStart;
        int end = chunkEnd == null ? start : chunkEnd;
        return start <= requestedEnd && end >= requestedStart;
    }

    private DocumentChunkRef toRef(DocumentManifest manifest, DocumentStructure.DocumentChunk chunk) {
        DocumentChunkRef ref = new DocumentChunkRef(
                manifest.documentId,
                manifest.researchGroupId,
                manifest.filename,
                chunk.pageStart,
                chunk.chunkIndex);
        ref.chunkId = chunk.chunkId;
        ref.sectionId = chunk.sectionId;
        ref.section = chunk.sectionTitle;
        ref.sectionPath = chunk.sectionPath;
        ref.title = chunk.title == null || chunk.title.isBlank() ? manifest.filename : chunk.title;
        ref.pageStart = chunk.pageStart;
        ref.pageEnd = chunk.pageEnd;
        ref.pageNumber = chunk.pageStart;
        ref.indexInSection = chunk.indexInSection;
        ref.prevChunkId = chunk.prevChunkId;
        ref.nextChunkId = chunk.nextChunkId;
        ref.storagePath = structurePath(manifest).toString();
        if (chunk.metadata != null && chunk.metadata.get("sourcePath") != null) {
            ref.sourcePath = chunk.metadata.get("sourcePath").toString();
        }
        return ref;
    }

    public void writeText(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text == null ? "" : text, StandardCharsets.UTF_8);
    }

    /**
     * 写入 JSON 文件（格式化输出）。
     *
     * @param path 文件路径
     * @param value 待序列化的对象
     * @throws IOException IO 异常
     */
    public void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        // 使用格式化输出（带缩进），方便人工查看和调试
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    /**
     * 生成页面文件名（1-based）。
     *
     * @param pageNumber 页码（从 1 开始）
     * @return 文件名（如 "page-0001.txt"）
     */
    public static String pageFileName(int pageNumber) {
        return "page-%04d.txt".formatted(pageNumber);
    }

    /**
     * 生成 chunk 文件名（0-based）。
     *
     * @param chunkIndex 块索引（从 0 开始）
     * @return 文件名（如 "chunk-0000.json"）
     */
    public static String chunkFileName(int chunkIndex) {
        return "chunk-%04d.json".formatted(chunkIndex);
    }

    /**
     * 将字符串转换为安全的目录名。
     *
     * <p>去除文件系统不允许的特殊字符（\ / : * ? " < > | 和空白字符），
     * 替换为下划线。空值返回 "default"。</p>
     *
     * @param value 原始字符串
     * @return 安全的目录名
     */
    public static String safeFolder(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    /**
     * 构建基础返回结果（从 manifest 中提取公共字段）。
     */
    private DocumentReadResult baseResult(DocumentManifest manifest) {
        DocumentReadResult result = new DocumentReadResult();
        result.documentId = manifest.documentId;
        result.filename = manifest.filename;
        result.researchGroupId = manifest.researchGroupId;
        return result;
    }

    /**
     * 将 chunk 的元数据转换为 DocumentChunkRef（引用溯源信息）。
     *
     * <p>DocumentChunkRef 记录了"这段内容来自哪个文档的哪个位置"，
     * 用于 RAG 回答时的引用标注。</p>
     */
    private DocumentChunkRef toRef(DocumentManifest manifest, Map<String, Object> chunk, int chunkIndex, Path chunkFile) {
        Map<String, Object> metadata = chunk.get("metadata") instanceof Map<?, ?> map
                ? copyMap(map)
                : Map.of();
        Integer pageNumber = asInteger(metadata.get("pageNumber"));
        DocumentChunkRef ref = new DocumentChunkRef(
                manifest.documentId, manifest.researchGroupId, manifest.filename, pageNumber, chunkIndex);
        ref.section = value(metadata, "section", pageNumber == null ? "document" : "page " + pageNumber);
        ref.title = value(metadata, "title", manifest.filename);
        ref.sourcePath = value(metadata, "sourcePath", null);
        ref.storagePath = chunkFile.toString();
        return ref;
    }

    /**
     * 安全地复制 Map（处理类型不兼容的问题）。
     */
    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * 安全地将 Object 转换为 Integer。
     *
     * <p>处理 Number 类型和字符串类型，转换失败时返回 null。</p>
     */
    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全地从 Map 中获取字符串值。
     *
     * @param map 数据源
     * @param key 键名
     * @param fallback 默认值
     * @return 字符串值，不存在或为空时返回默认值
     */
    private String value(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
