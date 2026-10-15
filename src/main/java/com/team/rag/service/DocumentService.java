package com.team.rag.service;

import com.team.rag.entity.DocumentManifest;
import com.team.rag.entity.DocumentChunkRef;
import com.team.rag.entity.DocumentStructure;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authoritative document-ingestion service.
 *
 * <p>Academic PDFs are parsed by GROBID into title/section/paragraph
 * structure and references are removed. PDFBox is the local fallback and
 * Tika handles non-PDF files. Each structure-aware chunk is written to both
 * dense and sparse Pinecone indexes with a stable chunk ID, while
 * {@link DocumentManifest} records lifecycle, hash and structure metadata.
 * The original upload and {@code structure.json} form the local structure
 * layer used for diagnostics and deterministic chunk reads.</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 嵌入模型：将文本 chunk 转换为稠密向量（如 OpenAI、BGE 等） */
    private final EmbeddingModel embeddingModel;
    /** 稠密向量存储：保存文本 chunk 对应的向量，支持语义相似度检索 */
    private final EmbeddingStore<TextSegment> embeddingStore;
    /** 文档解析器：基于 Apache Tika，支持 Word、TXT、HTML 等多种非 PDF 格式 */
    private final DocumentParser documentParser;
    /** 文档拆分器：将长文本按递归策略切分为固定大小的 chunk（支持重叠） */
    private final DocumentSplitter documentSplitter;
    /** 文档清单存储：持久化每个文档的元数据（哈希、状态、chunk 引用等） */
    private final DocumentManifestStore manifestStore;
    /** 文档读写服务：负责文件系统上的文本/chunk JSON 读写操作 */
    private final DocumentReadService documentReadService;
    /** 向量存储服务：封装了对 EmbeddingStore 的删除等管理操作 */
    private final VectorStoreService vectorStoreService;
    /** Pinecone 稀疏检索服务：维护稀疏向量索引，用于关键词精确匹配检索 */
    private final PineconeSparseRetrievalService pineconeSparseRetrievalService;
    private final GrobidDocumentParser grobidDocumentParser;
    /** PDF 章节检测器：从 PDF 的 Outline 或正则匹配中识别章节结构 */
    private final PdfSectionDetector sectionDetector;
    /** 章节检测开关：关闭时回退到逐页切分（默认开启） */
    private final boolean sectionDetectionEnabled;

    /**
     * 构造函数 —— 通过 Spring 依赖注入组装所有组件。
     *
     * @param embeddingModel              嵌入模型，将文本转为向量
     * @param embeddingStore              稠密向量存储
     * @param manifestStore               文档清单存储
     * @param documentReadService         文件系统读写服务
     * @param vectorStoreService          向量存储管理服务
     * @param pineconeSparseRetrievalService Pinecone 稀疏检索服务
     * @param chunkSize                   每个 chunk 的最大字符数（默认 500，可通过 team-rag.chunk-size 配置）
     * @param chunkOverlap                相邻 chunk 之间的重叠字符数（默认 50，可通过 team-rag.chunk-overlap 配置）
     */
    public DocumentService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            DocumentManifestStore manifestStore,
            DocumentReadService documentReadService,
            VectorStoreService vectorStoreService,
            PineconeSparseRetrievalService pineconeSparseRetrievalService,
            GrobidDocumentParser grobidDocumentParser,
            PdfSectionDetector sectionDetector,
            @Value("${team-rag.chunk-size:500}") int chunkSize,
            @Value("${team-rag.chunk-overlap:50}") int chunkOverlap,
            @Value("${team-rag.document.section-detection-enabled:true}") boolean sectionDetectionEnabled) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.manifestStore = manifestStore;
        this.documentReadService = documentReadService;
        this.vectorStoreService = vectorStoreService;
        this.pineconeSparseRetrievalService = pineconeSparseRetrievalService;
        this.grobidDocumentParser = grobidDocumentParser;
        this.sectionDetector = sectionDetector;
        this.sectionDetectionEnabled = sectionDetectionEnabled;
        this.documentParser = new ApacheTikaDocumentParser();
        this.documentSplitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        log.info("DocumentService initialized | chunkSize={}, chunkOverlap={}, sectionDetection={}",
                chunkSize, chunkOverlap, sectionDetectionEnabled);
    }

    /**
     * 上传并处理文档 —— 文档处理的主入口方法。
     *
     * <p>完整流程：上传 → 重复检测 → 解析 → 分块 → 向量化 → 存储 → 更新 Manifest。</p>
     *
     * <p>关键逻辑：</p>
     * <ol>
     *   <li>先计算 SHA-256 哈希，检查是否已存在相同文档；如果重复，只创建一条 DUPLICATED 记录，不重复索引。</li>
     *   <li>根据文件扩展名选择解析器：PDF 用 PDFBox 按页解析，其他用 Tika 统一解析。</li>
     *   <li>每页文本按 RecursiveSplitter 拆分为 chunk，每个 chunk 附带丰富的元数据（来源、页码、索引等）。</li>
     *   <li>所有 chunk 批量调用嵌入模型生成向量，写入稠密和稀疏两路索引。</li>
     *   <li>处理失败时 Manifest 状态标记为 FAILED，异常继续向上抛出。</li>
     * </ol>
     *
     * @param file             用户上传的文件（MultipartFile）
     * @param researchGroupId  研究组 ID，用于隔离不同组的文档
     * @return 成功处理的 chunk 数量；如果是重复文档，返回原始文档的 chunk 数量
     * @throws IOException 如果文件解析失败或文本为空
     */
    public int uploadAndProcessDocument(MultipartFile file, String researchGroupId) throws IOException {
        return uploadAndProcessDocument(file.getBytes(), file.getOriginalFilename(), researchGroupId);
    }

    /** Processes immutable staged bytes so asynchronous consumers never depend on request temp files. */
    public int uploadAndProcessDocument(byte[] bytes, String filename, String researchGroupId) throws IOException {
        // 如果文件名为空，给一个默认名称，避免后续空指针
        String displayName = filename == null || filename.isBlank() ? "document" : filename;
        long fileSize = bytes == null ? 0 : bytes.length;
        if (fileSize == 0) {
            throw new IOException("Document is empty.");
        }
        boolean isPdf = displayName.toLowerCase().endsWith(".pdf");
        // 用整个文件内容计算哈希，确保内容完全一致才算重复（即使文件名不同）
        String documentHash = sha256(bytes);
        // 重复检测：同一研究组内，只要哈希相同且非 DUPLICATED 状态，就认为是重复文档
        DocumentManifest existing = findDuplicate(researchGroupId, documentHash);
        if (existing != null) {
            // 重复文档不重新解析和索引，只创建一条 DUPLICATED 元数据记录，指向原始文档
            DocumentManifest duplicate = duplicateManifest(existing, displayName, fileSize, documentHash, researchGroupId);
            manifestStore.save(duplicate);
            log.info("Duplicate document skipped | filename={}, researchGroupId={}, documentId={}, duplicatedFrom={}",
                    filename, researchGroupId, duplicate.documentId, duplicate.duplicatedFrom);
            return existing.chunkCount;
        }

        // 生成确定性文档 ID：研究组 + 哈希组合后取前 16 位，确保同一文档在同组中 ID 一致
        String documentId = "doc_" + sha256String(safe(researchGroupId) + "|" + documentHash).substring(0, 16);
        Path documentDir = documentReadService.documentDirectory(researchGroupId, documentId);

        DocumentManifest manifest = new DocumentManifest();
        manifest.documentId = documentId;
        manifest.documentHash = documentHash;
        manifest.filename = displayName;
        manifest.researchGroupId = researchGroupId;
        manifest.fileSizeBytes = fileSize;
        manifest.parser = isPdf ? "pdfbox" : "tika";
        manifest.status = "PROCESSING";
        manifest.storagePath = documentDir.toString();
        manifestStore.save(manifest);

        log.info("Start document ingestion | filename={}, researchGroupId={}, documentId={}, sizeKB={}",
                filename, researchGroupId, documentId, fileSize / 1024);

        try {
            // 创建文档目录结构：{存储根}/{研究组}/{文档ID}/
            Files.createDirectories(documentDir);
            // 保存原始文件副本，便于日后下载或重新处理
            Path originalPath = documentDir.resolve("original").resolve(safeFilename(displayName));
            Files.createDirectories(originalPath.getParent());
            Files.write(originalPath, bytes);

            return ingestWithStructureLayer(
                    bytes,
                    isPdf,
                    displayName,
                    documentId,
                    documentHash,
                    researchGroupId,
                    documentDir,
                    originalPath,
                    manifest);
        } catch (IOException | RuntimeException e) {
            // 处理失败时将 Manifest 标记为 FAILED 并记录错误信息
            // 这样前端可以展示失败原因，运维也可以通过 Manifest 追踪问题文档
            manifest.status = "FAILED";
            manifest.errorMessage = e.getMessage();
            manifestStore.save(manifest);
            throw e; // 继续向上抛出，让控制器层决定如何响应用户
        }
    }

    /**
     * 删除文档 —— 同时清理检索层与结构层，确保文档被彻底移除。
     *
     * <p>清理顺序（由外到内，避免孤立数据）：</p>
     * <ol>
     *   <li><b>向量索引</b>：删除稠密向量（EmbeddingStore）和稀疏向量（Pinecone Sparse）中的关联数据。
     *       注意：DUPLICATED 状态的文档共享原始文档的向量，因此不删除向量。</li>
     *   <li><b>结构层</b>：递归删除文档目录（原始文件、结构化内容和 chunk JSON 等）。</li>
     *   <li><b>Manifest 记录</b>：删除文档清单，彻底断开引用。</li>
     * </ol>
     *
     * @param documentId 要删除的文档 ID
     * @return true 表示删除成功，false 表示未找到该文档的 Manifest
     */
    public boolean deleteDocument(String documentId) {
        DocumentManifest manifest = manifestStore.get(documentId);
        if (manifest == null) {
            log.warn("Delete failed: manifest not found | documentId={}", documentId);
            return false;
        }

        log.info("Deleting document | documentId={}, filename={}", documentId, manifest.filename);

        // 1. Delete retrieval indexes for non-duplicate documents
        if (!"DUPLICATED".equalsIgnoreCase(manifest.status)) {
            vectorStoreService.deleteByDocumentId(documentId);
            pineconeSparseRetrievalService.deleteDocument(manifest);
        }

        // 2. Delete filesystem directory
        if (manifest.storagePath != null && !manifest.storagePath.isBlank()) {
            try {
                Path storagePath = Path.of(manifest.storagePath);
                if (Files.exists(storagePath)) {
                    deleteRecursively(storagePath);
                    log.info("Document storage deleted | path={}", storagePath);
                }
            } catch (IOException e) {
                log.warn("Failed to delete document storage | path={}, error={}", manifest.storagePath, e.getMessage());
            }
        }

        // 3. Delete manifest
        manifestStore.delete(documentId);

        log.info("Document deleted successfully | documentId={}", documentId);
        return true;
    }

    /**
     * 递归删除文件或目录。
     *
     * <p>如果是目录，先遍历删除所有子文件/子目录，再删除目录本身。
     * 使用 try-with-resources 确保 DirectoryStream 被正确关闭，避免资源泄漏。</p>
     *
     * @param path 要删除的文件或目录路径
     * @throws IOException 如果删除过程中发生 I/O 错误
     */
    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }

    /**
     * 使用 Apache Tika 解析文档内容。
     *
     * <p>Tika 是一个通用文档解析库，支持 Word、Excel、TXT、HTML 等数十种格式。
     * 它会自动检测文件类型并提取纯文本。注意：Tika 将整个文档作为单篇文本返回，不区分页码。</p>
     *
     * @param bytes 文档的原始字节
     * @return 解析后的 LangChain4j Document 对象（包含纯文本）
     * @throws IOException 如果解析失败
     */

    private int ingestWithStructureLayer(byte[] bytes,
                                         boolean isPdf,
                                         String displayName,
                                         String documentId,
                                         String documentHash,
                                         String researchGroupId,
                                         Path documentDir,
                                         Path originalPath,
                                         DocumentManifest manifest) throws IOException {
        ParsedDocumentContent parsed = parseDocumentContent(bytes, isPdf, displayName);
        if (parsed.sections.isEmpty()) {
            throw new IOException("Document text is empty. It may be scanned, encrypted, or reference-only.");
        }

        String documentTitle = parsed.title == null || parsed.title.isBlank() ? displayName : parsed.title;
        Path structurePath = documentDir.resolve("structure.json");

        DocumentStructure structure = new DocumentStructure();
        structure.documentId = documentId;
        structure.researchGroupId = researchGroupId;
        structure.filename = displayName;
        structure.title = documentTitle;
        structure.authors = parsed.authors;
        structure.parser = parsed.parser;
        structure.parserVersion = parsed.parserVersion;
        structure.referencesRemoved = true;
        structure.pageCount = parsed.pageCount;

        List<TextSegment> segments = new ArrayList<>();
        List<DocumentChunkRef> chunkRefs = new ArrayList<>();
        List<Map<String, Object>> sparseRecords = new ArrayList<>();
        int chunkIndex = 0;

        for (ParsedSectionContent parsedSection : parsed.sections) {
            if (isReferencesTitle(parsedSection.title()) || parsedSection.text() == null || parsedSection.text().isBlank()) {
                continue;
            }

            DocumentStructure.DocumentSection section = new DocumentStructure.DocumentSection();
            section.sectionId = parsedSection.sectionId();
            section.parentSectionId = parsedSection.parentSectionId();
            section.title = parsedSection.title();
            section.normalizedTitle = normalizeTitle(parsedSection.title());
            section.sectionPath = parsedSection.sectionPath() == null || parsedSection.sectionPath().isBlank()
                    ? parsedSection.title()
                    : parsedSection.sectionPath();
            section.level = parsedSection.level();
            section.order = parsedSection.order();
            section.pageStart = parsedSection.pageStart();
            section.pageEnd = parsedSection.pageEnd();

            String sectionText = "Title: " + documentTitle + "\n"
                    + "Section: " + section.sectionPath + "\n\n"
                    + parsedSection.text();
            List<TextSegment> sectionSegments = documentSplitter.split(Document.from(sectionText));
            int indexInSection = 0;
            String previousChunkId = null;
            for (TextSegment segment : sectionSegments) {
                String chunkId = documentId + "::chunk::" + chunkIndex;
                DocumentStructure.DocumentChunk chunk = new DocumentStructure.DocumentChunk();
                chunk.chunkId = chunkId;
                chunk.documentId = documentId;
                chunk.sectionId = section.sectionId;
                chunk.title = documentTitle;
                chunk.sectionTitle = section.title;
                chunk.sectionPath = section.sectionPath;
                chunk.text = segment.text();
                chunk.chunkIndex = chunkIndex;
                chunk.indexInSection = indexInSection;
                chunk.pageStart = section.pageStart;
                chunk.pageEnd = section.pageEnd;
                chunk.prevChunkId = previousChunkId;
                chunk.tokenCount = approximateTokens(segment.text());

                segment.metadata().put("researchGroupId", researchGroupId);
                segment.metadata().put("source", displayName);
                segment.metadata().put("title", documentTitle);
                segment.metadata().put("section", section.title);
                segment.metadata().put("sectionId", section.sectionId);
                segment.metadata().put("sectionPath", section.sectionPath);
                segment.metadata().put("sourcePath", originalPath.toString());
                segment.metadata().put("documentId", documentId);
                segment.metadata().put("documentHash", documentHash);
                segment.metadata().put("filename", displayName);
                segment.metadata().put("chunkId", chunkId);
                segment.metadata().put("chunkIndex", String.valueOf(chunkIndex));
                segment.metadata().put("indexInSection", String.valueOf(indexInSection));
                segment.metadata().put("storagePath", structurePath.toString());
                if (section.pageStart != null) {
                    segment.metadata().put("pageNumber", String.valueOf(section.pageStart));
                    segment.metadata().put("pageStart", String.valueOf(section.pageStart));
                }
                if (section.pageEnd != null) {
                    segment.metadata().put("pageEnd", String.valueOf(section.pageEnd));
                }
                if (previousChunkId != null) {
                    segment.metadata().put("prevChunkId", previousChunkId);
                }

                if (!structure.chunks.isEmpty()) {
                    DocumentStructure.DocumentChunk previous = structure.chunks.get(structure.chunks.size() - 1);
                    if (section.sectionId.equals(previous.sectionId)) {
                        previous.nextChunkId = chunkId;
                    }
                }

                chunk.metadata.putAll(segment.metadata().toMap());
                structure.chunks.add(chunk);
                section.chunkIndexes.add(chunkIndex);

                DocumentChunkRef ref = new DocumentChunkRef(documentId, researchGroupId, displayName,
                        section.pageStart, chunkIndex);
                ref.chunkId = chunkId;
                ref.title = documentTitle;
                ref.section = section.title;
                ref.sectionId = section.sectionId;
                ref.sectionPath = section.sectionPath;
                ref.pageStart = section.pageStart;
                ref.pageEnd = section.pageEnd;
                ref.indexInSection = indexInSection;
                ref.prevChunkId = previousChunkId;
                ref.sourcePath = originalPath.toString();
                ref.storagePath = structurePath.toString();
                chunkRefs.add(ref);

                Map<String, Object> sparseRecord = buildSparseRecord(
                        documentId,
                        documentHash,
                        researchGroupId,
                        displayName,
                        section.title,
                        section.pageStart,
                        chunkIndex,
                        originalPath.toString(),
                        structurePath.toString(),
                        segment.text());
                sparseRecord.put("title", documentTitle);
                sparseRecord.put("chunkId", chunkId);
                sparseRecord.put("sectionId", section.sectionId);
                sparseRecord.put("sectionPath", section.sectionPath);
                if (section.pageStart != null) {
                    sparseRecord.put("pageStart", section.pageStart);
                }
                if (section.pageEnd != null) {
                    sparseRecord.put("pageEnd", section.pageEnd);
                }
                sparseRecords.add(sparseRecord);

                segments.add(segment);
                previousChunkId = chunkId;
                chunkIndex++;
                indexInSection++;
            }

            if (!section.chunkIndexes.isEmpty()) {
                structure.sections.add(section);
            }
        }

        for (DocumentStructure.DocumentChunk chunk : structure.chunks) {
            if (chunk.nextChunkId != null) {
                chunk.metadata.put("nextChunkId", chunk.nextChunkId);
            }
            if (chunk.prevChunkId != null) {
                chunk.metadata.put("prevChunkId", chunk.prevChunkId);
            }
        }
        for (int i = 0; i < chunkRefs.size(); i++) {
            DocumentStructure.DocumentChunk chunk = structure.chunks.get(i);
            chunkRefs.get(i).nextChunkId = chunk.nextChunkId;
        }

        if (segments.isEmpty()) {
            throw new IOException("Document did not produce any searchable chunks.");
        }

        structure.sectionCount = structure.sections.size();
        structure.chunkCount = structure.chunks.size();
        documentReadService.writeStructure(documentDir, structure);

        Response<List<Embedding>> embeddingResponse = embeddingModel.embedAll(segments);
        embeddingStore.addAll(embeddingResponse.content(), segments);
        pineconeSparseRetrievalService.upsertRecords(researchGroupId, sparseRecords);

        manifest.title = documentTitle;
        manifest.authors = parsed.authors;
        manifest.parser = parsed.parser;
        manifest.structurePath = structurePath.toString();
        manifest.structureVersion = "section-tree-v1";
        manifest.referencesRemoved = true;
        manifest.sectionDetectionMethod = parsed.sectionDetectionMethod;
        manifest.sectionCount = structure.sections.size();
        manifest.chunkCount = segments.size();
        manifest.pageCount = parsed.pageCount;
        manifest.chunkRefs = chunkRefs;
        manifest.status = "COMPLETED";
        manifestStore.save(manifest);

        log.info("Structure-layer ingestion completed | filename={}, documentId={}, parser={}, sections={}, chunks={}",
                displayName, documentId, parsed.parser, structure.sections.size(), segments.size());
        return segments.size();
    }

    private ParsedDocumentContent parseDocumentContent(byte[] bytes, boolean isPdf, String displayName) throws IOException {
        if (isPdf) {
            var grobidParsed = grobidDocumentParser.parsePdf(bytes, displayName);
            if (grobidParsed.isPresent()) {
                GrobidDocumentParser.ParsedAcademicDocument parsed = grobidParsed.get();
                ParsedDocumentContent result = new ParsedDocumentContent();
                result.title = parsed.title;
                result.authors = parsed.authors == null ? new ArrayList<>() : new ArrayList<>(parsed.authors);
                result.parser = parsed.parser;
                result.parserVersion = parsed.parserVersion;
                result.pageCount = countPdfPages(bytes);
                result.sectionDetectionMethod = "grobid";
                for (GrobidDocumentParser.ParsedSection section : parsed.sections) {
                    if (!isReferencesTitle(section.title())) {
                        result.sections.add(new ParsedSectionContent(
                                section.sectionId(),
                                section.parentSectionId(),
                                section.title(),
                                section.sectionPath(),
                                section.level(),
                                section.order(),
                                section.pageStart(),
                                section.pageEnd(),
                                section.text()));
                    }
                }
                if (!result.sections.isEmpty()) {
                    return result;
                }
            }
        }

        List<PageContent> pages = isPdf ? parsePdfPages(bytes) : parseSinglePage(bytes);
        String allText = pages.stream().map(page -> page.text).collect(Collectors.joining("\n"));
        if (allText == null || allText.isBlank()) {
            throw new IOException("Document text is empty. It may be scanned or encrypted.");
        }

        List<PdfSectionDetector.DetectedSection> detectedSections =
                (isPdf && sectionDetectionEnabled) ? sectionDetector.detect(bytes) : Collections.emptyList();
        List<SectionContent> sections = mergePagesBySection(pages, detectedSections);

        ParsedDocumentContent result = new ParsedDocumentContent();
        result.title = displayName;
        result.parser = isPdf ? "pdfbox" : "tika";
        result.parserVersion = isPdf ? "pdfbox-page-section-fallback" : "tika-single-section";
        result.pageCount = pages.size();
        result.sectionDetectionMethod = !isPdf ? "none"
                : (!sectionDetectionEnabled ? "disabled"
                : (detectedSections.isEmpty() ? "page_fallback" : "detected"));
        int order = 0;
        for (SectionContent section : sections) {
            String text = section.pages.stream()
                    .map(page -> page.text)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.joining("\n"));
            if (text.isBlank()) {
                continue;
            }
            int pageStart = section.pages.get(0).pageNumber;
            int pageEnd = section.pages.get(section.pages.size() - 1).pageNumber;
            String title = section.sectionTitle != null ? section.sectionTitle : "page " + pageStart;
            if (isReferencesTitle(title)) {
                continue;
            }
            String sectionId = "sec_" + order;
            result.sections.add(new ParsedSectionContent(
                    sectionId,
                    null,
                    title,
                    title,
                    1,
                    order,
                    pageStart,
                    pageEnd,
                    text));
            order++;
        }
        return result;
    }

    private int countPdfPages(byte[] bytes) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("upload-page-count-", ".pdf");
            Files.write(tempFile, bytes);
            try (PDDocument pdDocument = Loader.loadPDF(tempFile.toFile())) {
                return pdDocument.getNumberOfPages();
            }
        } catch (Exception e) {
            return 0;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    private boolean isReferencesTitle(String title) {
        if (title == null) {
            return false;
        }
        String lower = title.toLowerCase(java.util.Locale.ROOT).trim();
        return lower.equals("references")
                || lower.equals("reference")
                || lower.equals("bibliography")
                || lower.startsWith("references ")
                || lower.startsWith("bibliography ");
    }

    private String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private int approximateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private static class ParsedDocumentContent {
        String title;
        List<String> authors = new ArrayList<>();
        String parser;
        String parserVersion;
        String sectionDetectionMethod;
        int pageCount;
        List<ParsedSectionContent> sections = new ArrayList<>();
    }

    private record ParsedSectionContent(
            String sectionId,
            String parentSectionId,
            String title,
            String sectionPath,
            int level,
            int order,
            Integer pageStart,
            Integer pageEnd,
            String text) {
    }

    private Document parseWithTika(byte[] bytes) throws IOException {
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            return documentParser.parse(inputStream);
        }
    }

    /**
     * 将非 PDF 文档解析为单页内容。
     *
     * <p>非 PDF 文档（如 Word、TXT）没有"页"的概念，因此将整篇文档视为第 1 页。
     * 这样统一了后续的分页处理逻辑，使 PDF 和非 PDF 走同一条代码路径。</p>
     *
     * @param bytes 文档的原始字节
     * @return 包含单个 PageContent 的列表（pageNumber=1）
     * @throws IOException 如果解析失败
     */
    private List<PageContent> parseSinglePage(byte[] bytes) throws IOException {
        Document document = parseWithTika(bytes);
        return List.of(new PageContent(1, document.text()));
    }

    /**
     * 使用 PDFBox 按页解析 PDF 文档。
     *
     * <p>为什么用 PDFBox 而不是 Tika 解析 PDF？因为 PDFBox 可以精确控制页码范围，
     * 将每页文本单独提取出来，这样每个 chunk 都能关联到具体的页码，提升检索结果的可定位性。</p>
     *
     * <p>处理流程：写入临时文件 → 逐页设置 stripper 的 startPage/endPage → 提取每页文本 → 清理临时文件。</p>
     *
     * @param bytes PDF 文件的原始字节
     * @return 按页排列的 PageContent 列表（页码从 1 开始）
     * @throws IOException 如果 PDF 解析失败
     */
    private List<PageContent> parsePdfPages(byte[] bytes) throws IOException {
        // PDFBox 要求传入 File 对象，所以必须先写入临时文件
        Path tempFile = Files.createTempFile("upload-", ".pdf");
        try {
            Files.write(tempFile, bytes);
            try (PDDocument pdDocument = Loader.loadPDF(tempFile.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);    // 按位置排序文字，保持阅读顺序
                stripper.setAddMoreFormatting(true); // 添加更多格式信息（如段落分隔）
                stripper.setLineSeparator("\n");     // 统一换行符
                List<PageContent> pages = new ArrayList<>();
                int pageCount = pdDocument.getNumberOfPages();
                // 逐页提取文本：通过设置 startPage/endPage 为同一页，实现单页提取
                for (int page = 1; page <= pageCount; page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    pages.add(new PageContent(page, stripper.getText(pdDocument)));
                }
                int totalLength = pages.stream().mapToInt(item -> item.text == null ? 0 : item.text.length()).sum();
                log.info("PDF parsed | pages={}, textLength={}", pageCount, totalLength);
                return pages;
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 使用 PDFBox 将整个 PDF 解析为单篇文档（不分页）。
     *
     * <p>这是 parsePdfPages 的简化版本，适用于不需要按页拆分的场景。
     * 当前未被主流程调用，保留作为备选解析方式。</p>
     *
     * @param bytes PDF 文件的原始字节
     * @return 解析后的 LangChain4j Document 对象（包含全文）
     * @throws IOException 如果 PDF 解析失败
     */
    private Document parsePdfWithPdfBox(byte[] bytes) throws IOException {
        Path tempFile = Files.createTempFile("upload-", ".pdf");
        try {
            Files.write(tempFile, bytes);
            try (PDDocument pdDocument = Loader.loadPDF(tempFile.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setAddMoreFormatting(true);
                stripper.setLineSeparator("\n");
                String text = stripper.getText(pdDocument);
                log.info("PDF parsed | pages={}, textLength={}", pdDocument.getNumberOfPages(), text.length());
                return Document.from(text);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 在同一研究组中查找与给定哈希匹配的原始文档（非 DUPLICATED 状态）。
     *
     * <p>过滤逻辑：先按哈希匹配，再排除 DUPLICATED 状态的记录。
     * 因为 DUPLICATED 记录只是"指针"，不能作为重复检测的基准。</p>
     *
     * @param researchGroupId 研究组 ID
     * @param documentHash    文档的 SHA-256 哈希
     * @return 匹配的原始文档 Manifest，如果没有找到返回 null
     */
    private DocumentManifest findDuplicate(String researchGroupId, String documentHash) {
        return manifestStore.list(researchGroupId).stream()
                .filter(item -> documentHash.equals(item.documentHash))
                .filter(item -> !"DUPLICATED".equalsIgnoreCase(item.status))
                .findFirst()
                .orElse(null);
    }

    /**
     * 为重复文档创建一条 DUPLICATED 状态的 Manifest 记录。
     *
     * <p>重复文档不重新解析和索引，而是共享原始文档的 chunk 数据和向量。
     * 这样既节省了存储和计算资源，又能让用户在界面上看到"已上传过"的提示。</p>
     *
     * <p>DUPLICATED 记录的关键字段：</p>
     * <ul>
     *   <li>documentId: 在原始 ID 基础上追加 _dup_ 随机后缀，避免 ID 冲突</li>
     *   <li>duplicatedFrom: 指向原始文档的 ID，建立关联关系</li>
     *   <li>status: 标记为 "DUPLICATED"，删除时跳过向量清理</li>
     *   <li>chunkRefs/storagePath: 复用原始文档的引用，不创建新文件</li>
     * </ul>
     *
     * @param existing        原始文档的 Manifest
     * @param filename        重复上传时使用的文件名（可能与原始不同）
     * @param fileSizeBytes   重复上传文件的大小
     * @param documentHash    文档哈希（与原始相同）
     * @param researchGroupId 研究组 ID
     * @return 新创建的 DUPLICATED 状态 Manifest
     * @throws IOException 如果哈希计算失败
     */
    private DocumentManifest duplicateManifest(DocumentManifest existing, String filename, long fileSizeBytes,
                                               String documentHash, String researchGroupId) throws IOException {
        DocumentManifest duplicate = new DocumentManifest();
        duplicate.documentId = existing.documentId + "_dup_" + UUID.randomUUID().toString().substring(0, 8);
        duplicate.documentHash = documentHash;
        duplicate.filename = filename;
        duplicate.title = existing.title;
        duplicate.authors = existing.authors;
        duplicate.researchGroupId = researchGroupId;
        duplicate.fileSizeBytes = fileSizeBytes;
        duplicate.chunkCount = existing.chunkCount;
        duplicate.pageCount = existing.pageCount;
        duplicate.storagePath = existing.storagePath;
        duplicate.structurePath = existing.structurePath;
        duplicate.structureVersion = existing.structureVersion;
        duplicate.chunkRefs = existing.chunkRefs;
        duplicate.duplicatedFrom = existing.documentId;
        duplicate.parser = existing.parser;
        duplicate.sectionDetectionMethod = existing.sectionDetectionMethod;
        duplicate.sectionCount = existing.sectionCount;
        duplicate.referencesRemoved = existing.referencesRemoved;
        duplicate.status = "DUPLICATED";
        return duplicate;
    }

    /**
     * 计算字节数组的 SHA-256 哈希值。
     *
     * <p>SHA-256 是一种密码学哈希算法，具有以下特性：</p>
     * <ul>
     *   <li>相同输入始终产生相同输出（确定性）</li>
     *   <li>不同输入产生不同输出（抗碰撞）</li>
     *   <li>无法从哈希反推原始内容（不可逆）</li>
     * </ul>
     * <p>用于文档去重：只要文件内容相同，哈希就相同，无论文件名是否一致。</p>
     *
     * @param bytes 要计算哈希的字节数组
     * @return 64 位十六进制字符串（小写）
     * @throws IOException 如果 SHA-256 算法不可用
     */
    private String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Unable to hash document", e);
        }
    }

    /**
     * 计算字符串的 SHA-256 哈希值（UTF-8 编码）。
     *
     * <p>主要用于生成确定性文档 ID：将研究组 ID 和文档哈希拼接后哈希，
     * 确保同一文档在同一组中总是得到相同的 ID。</p>
     *
     * @param value 要哈希的字符串
     * @return 64 位十六进制哈希字符串
     * @throws IOException 如果哈希计算失败
     */
    private String sha256String(String value) throws IOException {
        return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 安全处理字符串：null 或空白时返回 "default"。
     *
     * <p>用于处理可能为空的研究组 ID，确保哈希计算不会因空值而出错。</p>
     *
     * @param value 原始字符串
     * @return 处理后的安全字符串，不会为 null 或空白
     */
    private String safe(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    /**
     * 将文件名中的非法字符替换为下划线，确保可以安全用于文件系统路径。
     *
     * <p>Windows 和 Linux 文件名中不允许的字符包括：\ / : * ? " < > |</p>
     * <p>如果文件名为空或空白，返回默认名称 "document.txt"。</p>
     *
     * @param filename 原始文件名
     * @return 安全的文件名
     */
    private String safeFilename(String filename) {
        String safeName = filename == null || filename.isBlank() ? "document.txt" : filename.trim();
        return safeName.replaceAll("[\\\\/:*?\"<>|]+", "_");
    }

    /**
     * 构建 Pinecone 稀疏检索的单条记录。
     *
     * <p>稀疏向量用于关键词精确匹配召回，与稠密向量的语义检索形成互补。
     * 每条记录包含文档元数据和文本内容，由 Pinecone 自动构建稀疏索引。</p>
     *
     * @param documentId      文档 ID
     * @param documentHash    文档哈希
     * @param researchGroupId 研究组 ID
     * @param filename        文件名
     * @param pageNumber      页码
     * @param chunkIndex      chunk 索引（全局递增）
     * @param sourcePath      原始文件路径
     * @param storagePath     chunk JSON 文件路径
     * @param text            chunk 的文本内容
     * @return 包含所有字段的 Map，可直接序列化为 JSON 发送到 Pinecone
     */
    private Map<String, Object> buildSparseRecord(String documentId,
                                                  String documentHash,
                                                  String researchGroupId,
                                                  String filename,
                                                  String sectionName,
                                                  Integer pageNumber,
                                                  int chunkIndex,
                                                  String sourcePath,
                                                  String storagePath,
                                                  String text) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("_id", pineconeSparseRetrievalService.recordId(documentId, chunkIndex));
        record.put("title", filename);
        record.put("filename", filename);
        record.put("section", sectionName);
        record.put(pineconeSparseRetrievalService.textField(), text);
        record.put("documentId", documentId);
        record.put("documentHash", documentHash);
        record.put("researchGroupId", researchGroupId);
        if (pageNumber != null) {
            record.put("pageNumber", pageNumber);
        }
        record.put("chunkIndex", chunkIndex);
        record.put("sourcePath", sourcePath);
        record.put("storagePath", storagePath);
        return record;
    }

    /**
     * 将逐页内容按章节结构合并。
     *
     * <p>如果章节检测成功，属于同一章节的多个页面会被合并为一个 SectionContent，
     * 后续在章节级别做 RecursiveSplitter 切分，避免跨页语义断裂。</p>
     *
     * <p>如果章节检测失败（detectedSections 为空），每个页面作为独立章节返回，
     * 保持与原有逐页切分一致的行为。</p>
     *
     * @param pages             逐页解析结果
     * @param detectedSections  已检测到的章节结构（空列表表示检测失败）
     * @return 按章节组织的内容列表
     */
    private List<SectionContent> mergePagesBySection(List<PageContent> pages,
                                                     List<PdfSectionDetector.DetectedSection> detectedSections) {
        if (detectedSections.isEmpty()) {
            // 检测失败：每个页面作为独立章节（回退到逐页行为）
            return pages.stream()
                    .map(p -> new SectionContent(null, List.of(p)))
                    .toList();
        }

        // 按章节合并页面
        List<SectionContent> result = new ArrayList<>();
        for (PdfSectionDetector.DetectedSection section : detectedSections) {
            List<PageContent> sectionPages = pages.stream()
                    .filter(p -> p.pageNumber >= section.startPage() && p.pageNumber <= section.endPage())
                    .toList();
            if (!sectionPages.isEmpty()) {
                result.add(new SectionContent(section.title(), sectionPages));
            }
        }

        // 处理不属于任何章节的页面（如封面、参考文献之前的空白页）
        List<Integer> coveredPages = detectedSections.stream()
                .flatMap(s -> java.util.stream.IntStream.rangeClosed(s.startPage(), s.endPage()).boxed())
                .toList();
        List<PageContent> uncoveredPages = pages.stream()
                .filter(p -> !coveredPages.contains(p.pageNumber))
                .toList();
        if (!uncoveredPages.isEmpty()) {
            // 将未覆盖的页面插到最前面（通常是封面/摘要前的内容）
            result.add(0, new SectionContent(null, uncoveredPages));
        }

        return result;
    }

    /**
     * 章节内容的内部数据类 —— 封装章节标题和属于该章节的所有页面。
     *
     * <p>当章节检测成功时，sectionTitle 为检测到的标题（如 "1. Introduction"）；
     * 当回退到逐页模式时，sectionTitle 为 null，每个 SectionContent 只包含一个页面。</p>
     */
    private static class SectionContent {
        final String sectionTitle;
        final List<PageContent> pages;

        SectionContent(String sectionTitle, List<PageContent> pages) {
            this.sectionTitle = sectionTitle;
            this.pages = pages;
        }
    }

    /**
     * 页面内容的内部数据类 —— 封装单页的页码和文本。
     *
     * <p>用于统一 PDF 按页解析和非 PDF 整篇解析的返回格式。
     * PDF 每页对应一个 PageContent，非 PDF 只有一个 pageNumber=1 的实例。</p>
     */
    private static class PageContent {
        final int pageNumber;
        final String text;

        PageContent(int pageNumber, String text) {
            this.pageNumber = pageNumber;
            this.text = text == null ? "" : text;
        }
    }
}
