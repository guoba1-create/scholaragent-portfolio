package com.team.rag.controller;

import com.team.rag.entity.DocumentManifest;
import com.team.rag.entity.DocumentDiagnosticsResult;
import com.team.rag.entity.DocumentReadResult;
import com.team.rag.identity.RequestIdentity;
import com.team.rag.identity.RequestIdentityResolver;
import com.team.rag.service.AsyncDocumentProcessService;
import com.team.rag.service.DocumentDiagnosticsService;
import com.team.rag.service.DocumentManifestStore;
import com.team.rag.service.DocumentReadService;
import com.team.rag.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档控制器 - 管理文档的上传、处理、查询和删除。
 *
 * <p>该控制器提供文档全生命周期管理功能，包括异步文档处理、
 * 状态查询、文档诊断、内容读取等操作。</p>
 *
 * <h3>REST 端点概述：</h3>
 * <ul>
 *   <li>{@code POST /api/documents/upload} - 上传文档并启动异步处理</li>
 *   <li>{@code GET /api/documents/status/{taskId}} - 查询文档处理任务状态</li>
 *   <li>{@code GET /api/documents/tasks} - 获取所有处理任务列表</li>
 *   <li>{@code GET /api/documents/manifests} - 列出文档清单</li>
 *   <li>{@code GET /api/documents/manifests/{documentId}} - 获取指定文档清单</li>
 *   <li>{@code DELETE /api/documents/{documentId}} - 删除文档</li>
 *   <li>{@code GET /api/documents/{documentId}/diagnostics} - 文档诊断</li>
 *   <li>{@code GET /api/documents/{documentId}/read} - 读取文档页面</li>
 *   <li>{@code GET /api/documents/{documentId}/chunks/{chunkIndex}} - 读取文档分块</li>
 * </ul>
 *
 * @see AsyncDocumentProcessService
 * @see DocumentManifestStore
 * @see DocumentService
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    /** 异步文档处理服务，负责文档的异步解析和索引 */
    private final AsyncDocumentProcessService asyncDocumentProcessService;

    /** 文档清单存储，管理文档的元数据信息 */
    private final DocumentManifestStore manifestStore;

    /** 文档读取服务，提供文档内容读取功能 */
    private final DocumentReadService documentReadService;

    /** 文档诊断服务，检查文档处理状态和质量 */
    private final DocumentDiagnosticsService documentDiagnosticsService;

    /** 文档服务，提供文档的核心 CRUD 操作 */
    private final DocumentService documentService;
    private final RequestIdentityResolver identityResolver;

    /**
     * 构造函数，注入文档相关的服务组件。
     *
     * @param asyncDocumentProcessService 异步文档处理服务
     * @param manifestStore               文档清单存储
     * @param documentReadService         文档读取服务
     * @param documentDiagnosticsService  文档诊断服务
     * @param documentService             文档服务
     */
    public DocumentController(
            AsyncDocumentProcessService asyncDocumentProcessService,
            DocumentManifestStore manifestStore,
            DocumentReadService documentReadService,
            DocumentDiagnosticsService documentDiagnosticsService,
            DocumentService documentService,
            RequestIdentityResolver identityResolver) {
        this.asyncDocumentProcessService = asyncDocumentProcessService;
        this.manifestStore = manifestStore;
        this.documentReadService = documentReadService;
        this.documentDiagnosticsService = documentDiagnosticsService;
        this.documentService = documentService;
        this.identityResolver = identityResolver;
    }

    /**
     * 上传文档并启动异步处理。
     *
     * <p>接收上传的文件和研究组 ID，创建异步处理任务。
     * 文档将在后台进行解析、分块和索引处理。</p>
     *
     * <p>HTTP 方法：POST</p>
     * <p>路径：/api/documents/upload</p>
     *
     * @param file           上传的文件，支持 PDF、Word 等格式
     * @param researchGroupId 研究组 ID，用于文档归属管理
     * @return 包含任务 ID、文件名、状态等信息的响应
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "researchGroupId", required = false) String ignoredResearchGroupId,
            HttpServletRequest servletRequest) {
        String researchGroupId = identityResolver.resolve(servletRequest).knowledgeSpaceId();
        // 验证文件参数
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "file is required"));
        }
        // 验证研究组 ID 参数
        if (researchGroupId == null || researchGroupId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "researchGroupId is required"));
        }

        // 获取文件名并生成任务 ID
        String filename = file.getOriginalFilename();
        String taskId = UUID.randomUUID().toString();
        log.info("Document upload accepted | filename={}, researchGroupId={}, sizeKB={}, taskId={}",
                filename, researchGroupId, file.getSize() / 1024, taskId);

        try {
            // 启动异步文档处理任务
            asyncDocumentProcessService.processDocumentAsync(taskId, file, researchGroupId, filename);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Document accepted and processing started.",
                    "taskId", taskId,
                    "filename", filename == null ? "" : filename,
                    "researchGroupId", researchGroupId,
                    "status", "PROCESSING"));
        } catch (Exception e) {
            // 处理失败，记录错误日志并返回错误响应
            log.error("Document upload failed | filename={}, error={}", filename, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Document upload failed: " + e.getMessage()));
        }
    }

    /**
     * 查询文档处理任务状态。
     *
     * <p>根据任务 ID 查询文档处理的当前状态，包括处理进度、分块数量、错误信息等。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/documents/status/{taskId}</p>
     *
     * @param taskId 任务唯一标识符
     * @return 包含任务状态信息的响应，如果任务不存在返回 404
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId,
                                                             HttpServletRequest servletRequest) {
        // 获取任务状态
        AsyncDocumentProcessService.ProcessTask task = asyncDocumentProcessService.getTaskStatus(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        assertSpaceAccess(task.getResearchGroupId(), servletRequest);

        // 返回任务状态信息
        return ResponseEntity.ok(Map.of(
                "success", true,
                "taskId", task.getTaskId(),
                "filename", task.getFilename(),
                "researchGroupId", task.getResearchGroupId(),
                "status", task.getStatus(),
                "segmentCount", task.getSegmentCount(),
                "errorMessage", task.getErrorMessage() == null ? "" : task.getErrorMessage(),
                "durationMs", task.getDurationMs()));
    }

    /**
     * 获取所有文档处理任务列表。
     *
     * <p>返回所有文档处理任务的列表，按开始时间倒序排列（最新的任务在前）。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/documents/tasks</p>
     *
     * @return 包含任务列表的响应
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getAllTasks(HttpServletRequest servletRequest) {
        String spaceId = identityResolver.resolve(servletRequest).knowledgeSpaceId();
        // 获取所有任务并按开始时间倒序排列
        List<AsyncDocumentProcessService.ProcessTask> tasks = asyncDocumentProcessService.getAllTasks()
                .values()
                .stream()
                .filter(task -> spaceId.equals(task.getResearchGroupId()))
                .sorted(Comparator.comparing(AsyncDocumentProcessService.ProcessTask::getStartTime).reversed())
                .toList();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "tasks", tasks));
    }

    /**
     * 列出文档清单。
     *
     * <p>根据研究组 ID（可选）查询文档清单列表。
     * 如果不指定研究组 ID，则返回所有文档清单。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/documents/manifests</p>
     *
     * @param researchGroupId 研究组 ID（可选），用于筛选特定研究组的文档
     * @return 包含文档清单列表的响应
     */
    @GetMapping("/manifests")
    public ResponseEntity<Map<String, Object>> listManifests(
            @RequestParam(value = "researchGroupId", required = false) String ignoredResearchGroupId,
            HttpServletRequest servletRequest) {
        String researchGroupId = identityResolver.resolve(servletRequest).knowledgeSpaceId();
        // 获取文档清单列表
        List<DocumentManifest> manifests = manifestStore.list(researchGroupId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "researchGroupId", researchGroupId == null ? "" : researchGroupId,
                "count", manifests.size(),
                "manifests", manifests));
    }

    /**
     * 获取指定文档的清单信息。
     *
     * <p>根据文档 ID 获取文档的详细清单信息，包括元数据、处理状态等。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/documents/manifests/{documentId}</p>
     *
     * @param documentId 文档唯一标识符
     * @return 包含文档清单的响应，如果文档不存在返回 404
     */
    @GetMapping("/manifests/{documentId}")
    public ResponseEntity<Map<String, Object>> getManifest(@PathVariable String documentId,
                                                           HttpServletRequest servletRequest) {
        // 获取文档清单
        DocumentManifest manifest = manifestStore.get(documentId);
        if (manifest == null) {
            return ResponseEntity.notFound().build();
        }
        assertSpaceAccess(manifest.researchGroupId, servletRequest);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "manifest", manifest));
    }

    /**
     * 删除文档。
     *
     * <p>根据文档 ID 删除文档及其相关数据，包括索引、分块等。</p>
     *
     * <p>HTTP 方法：DELETE</p>
     * <p>路径：/api/documents/{documentId}</p>
     *
     * @param documentId 文档唯一标识符
     * @return 删除结果响应，成功返回文档 ID，失败返回错误信息
     */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable String documentId,
                                                              HttpServletRequest servletRequest) {
        assertDocumentAccess(documentId, servletRequest);
        log.info("Delete document request | documentId={}", documentId);
        try {
            // 执行文档删除操作
            boolean deleted = documentService.deleteDocument(documentId);
            if (deleted) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Document deleted successfully.",
                        "documentId", documentId));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            // 删除失败，记录错误日志
            log.error("Delete document failed | documentId={}, error={}", documentId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Delete failed: " + e.getMessage()));
        }
    }

    /**
     * 获取文档诊断信息。
     *
     * <p>对指定文档进行诊断，检查文档处理状态、索引质量、分块情况等。
     * 用于排查文档处理问题和验证索引质量。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/documents/{documentId}/diagnostics</p>
     *
     * @param documentId 文档唯一标识符
     * @return 文档诊断结果，包含处理状态、错误信息等
     */
    @GetMapping("/{documentId}/diagnostics")
    public ResponseEntity<DocumentDiagnosticsResult> getDocumentDiagnostics(@PathVariable String documentId,
                                                                            HttpServletRequest servletRequest) {
        assertDocumentAccess(documentId, servletRequest);
        // 执行文档诊断
        DocumentDiagnosticsResult result = documentDiagnosticsService.diagnose(documentId);
        // 如果诊断失败且无法确定有效文档 ID，返回 404
        if (!result.success && result.effectiveDocumentId == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 读取文档页面内容。
     *
     * <p>根据文档 ID 和页码范围读取文档的指定页面内容。
     * 如果不指定页码范围，则读取整个文档。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/documents/{documentId}/read</p>
     *
     * @param documentId 文档唯一标识符
     * @param pageStart  起始页码（可选）
     * @param pageEnd    结束页码（可选）
     * @return 文档读取结果，包含页面内容和元数据
     */
    @GetMapping("/{documentId}/read")
    public ResponseEntity<DocumentReadResult> readDocumentPages(
            @PathVariable String documentId,
            @RequestParam(value = "pageStart", required = false) Integer pageStart,
            @RequestParam(value = "pageEnd", required = false) Integer pageEnd,
            HttpServletRequest servletRequest) {
        assertDocumentAccess(documentId, servletRequest);
        // 读取指定页码范围的文档内容
        DocumentReadResult result = documentReadService.readPages(documentId, pageStart, pageEnd);
        // 如果文档清单不存在，返回 404
        if (!result.success && "Document manifest not found.".equals(result.errorMessage)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 读取文档分块内容。
     *
     * <p>根据文档 ID 和分块索引读取文档的指定分块内容。
     * 文档在处理时会被分割成多个分块，此接口用于读取单个分块。</p>
     *
     * <p>HTTP 方法：GET</p>
     * <p>路径：/api/documents/{documentId}/chunks/{chunkIndex}</p>
     *
     * @param documentId 文档唯一标识符
     * @param chunkIndex 分块索引，从 0 开始
     * @return 文档分块读取结果，包含分块内容和元数据
     */
    @GetMapping("/{documentId}/chunks/{chunkIndex}")
    public ResponseEntity<DocumentReadResult> readDocumentChunk(
            @PathVariable String documentId,
            @PathVariable int chunkIndex,
            HttpServletRequest servletRequest) {
        assertDocumentAccess(documentId, servletRequest);
        // 读取指定索引的文档分块
        DocumentReadResult result = documentReadService.readChunk(documentId, chunkIndex);
        // 如果文档清单不存在，返回 404
        if (!result.success && "Document manifest not found.".equals(result.errorMessage)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    private void assertDocumentAccess(String documentId, HttpServletRequest request) {
        DocumentManifest manifest = manifestStore.get(documentId);
        if (manifest == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
        }
        assertSpaceAccess(manifest.researchGroupId, request);
    }

    private void assertSpaceAccess(String documentSpaceId, HttpServletRequest request) {
        RequestIdentity identity = identityResolver.resolve(request);
        if (!identity.knowledgeSpaceId().equals(documentSpaceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Document belongs to another knowledge space.");
        }
    }
}
