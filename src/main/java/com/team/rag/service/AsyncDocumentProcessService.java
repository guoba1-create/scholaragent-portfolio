package com.team.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.queue.DocumentIngestTask;
import com.team.rag.queue.DocumentIngestionProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable document-ingestion entry point. Upload bytes are staged before the
 * HTTP request returns, while Kafka transports only a small idempotent task reference.
 */
@Service
public class AsyncDocumentProcessService {

    private static final Logger log = LoggerFactory.getLogger(AsyncDocumentProcessService.class);
    private static final long TASK_MAX_AGE_MS = Duration.ofDays(7).toMillis();
    private static final long TASK_CLEANUP_INTERVAL = 32L;

    private final DocumentService documentService;
    private final StatsService statsService;
    private final DocumentIngestionProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
    private final Map<String, ProcessTask> taskStatusMap = new ConcurrentHashMap<>();
    private final Set<String> activeTaskIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong taskAccessCounter = new AtomicLong();
    private final ThreadPoolExecutor localExecutor;

    public AsyncDocumentProcessService(DocumentService documentService,
                                       StatsService statsService,
                                       DocumentIngestionProperties properties,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
        this.documentService = documentService;
        this.statsService = statsService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.localExecutor = new ThreadPoolExecutor(
                properties.localThreads(), properties.localThreads(), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.localQueueCapacity()), runnable -> {
                    Thread thread = new Thread(runnable, "document-ingest-local");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        cleanupStagingArtifacts(Duration.ofHours(properties.stagingMaxAgeHours()).toMillis());
    }

    /** Stages the upload synchronously, then dispatches a reference through Kafka or local fallback. */
    public void processDocumentAsync(String taskId, MultipartFile file, String knowledgeSpaceId,
                                     String originalFilename) throws IOException {
        if (file.getSize() > properties.maxFileBytes()) {
            throw new IOException("Document exceeds the configured upload limit.");
        }
        byte[] bytes = file.getBytes();
        String safeTaskId = requireTaskId(taskId);
        String filename = safeFilename(originalFilename);
        Path taskDirectory = properties.stagingDirectory().toAbsolutePath().normalize().resolve(safeTaskId);
        Files.createDirectories(taskDirectory);
        Path staged = taskDirectory.resolve(filename).normalize();
        if (!staged.startsWith(taskDirectory)) {
            throw new IOException("Invalid document staging path.");
        }
        Files.write(staged, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        DocumentIngestTask task = new DocumentIngestTask();
        task.taskId = safeTaskId;
        task.filename = filename;
        task.knowledgeSpaceId = knowledgeSpaceId;
        task.stagingPath = staged.toString();
        task.contentHash = sha256(bytes);
        task.sizeBytes = bytes.length;
        taskStatusMap.put(task.taskId, new ProcessTask(task.taskId, filename, knowledgeSpaceId));
        maybeCleanupTasks();

        if (!publishKafka(task)) {
            enqueueLocal(task);
        }
    }

    @KafkaListener(
            topics = "${document.ingest.kafka.topic:document.ingest}",
            groupId = "${document.ingest.kafka.group-id:document-indexer-v1}",
            autoStartup = "${document.ingest.kafka.enabled:true}")
    public void consumeKafka(String payload) {
        try {
            process(objectMapper.readValue(payload, DocumentIngestTask.class), "kafka");
        } catch (Exception error) {
            log.error("Document ingest Kafka message failed | error={}", error.getMessage(), error);
            publishDlq(payload, error);
        }
    }

    public ProcessTask getTaskStatus(String taskId) {
        maybeCleanupTasks();
        return taskStatusMap.get(taskId);
    }

    public Map<String, ProcessTask> getAllTasks() {
        maybeCleanupTasks();
        return new ConcurrentHashMap<>(taskStatusMap);
    }

    public void cleanupOldTasks(long maxAgeMs) {
        long now = System.currentTimeMillis();
        taskStatusMap.entrySet().removeIf(entry -> {
            ProcessTask task = entry.getValue();
            return task.getEndTime() != null && now - task.getEndTime() > maxAgeMs;
        });
    }

    @PreDestroy
    public void shutdown() {
        localExecutor.shutdownNow();
    }

    private boolean publishKafka(DocumentIngestTask task) {
        if (!properties.kafkaEnabled()) return false;
        try {
            KafkaTemplate<String, String> template = kafkaTemplateProvider.getIfAvailable();
            if (template == null) return false;
            template.send(properties.topic(), task.taskId, objectMapper.writeValueAsString(task))
                    .get(5, TimeUnit.SECONDS);
            ProcessTask status = taskStatusMap.get(task.taskId);
            if (status != null) status.setQueueMode("kafka");
            log.info("Document ingest queued | taskId={}, topic={}", task.taskId, properties.topic());
            return true;
        } catch (Exception error) {
            log.warn("Document Kafka enqueue failed; using local fallback | taskId={}, error={}",
                    task.taskId, error.getMessage());
            return false;
        }
    }

    private void enqueueLocal(DocumentIngestTask task) {
        ProcessTask status = taskStatusMap.get(task.taskId);
        if (status != null) status.setQueueMode("local-fallback");
        try {
            localExecutor.execute(() -> process(task, "local-fallback"));
        } catch (RuntimeException error) {
            markFailed(task, "Local ingestion queue is full: " + error.getMessage());
            throw error;
        }
    }

    private void process(DocumentIngestTask task, String queueMode) {
        if (task == null || task.taskId == null || !activeTaskIds.add(task.taskId)) {
            return;
        }
        ProcessTask status = taskStatusMap.computeIfAbsent(task.taskId,
                id -> new ProcessTask(id, task.filename, task.knowledgeSpaceId));
        status.setQueueMode(queueMode);
        status.setStatus("PROCESSING");
        status.setStartTime(System.currentTimeMillis());
        try {
            Path root = properties.stagingDirectory().toAbsolutePath().normalize();
            Path staged = Path.of(task.stagingPath).toAbsolutePath().normalize();
            if (!staged.startsWith(root) || !Files.isRegularFile(staged)) {
                throw new IOException("Staged document is missing or outside the ingestion directory.");
            }
            byte[] bytes = Files.readAllBytes(staged);
            if (!sha256(bytes).equals(task.contentHash)) {
                throw new IOException("Staged document hash mismatch.");
            }
            int segmentCount = documentService.uploadAndProcessDocument(
                    bytes, task.filename, task.knowledgeSpaceId);
            statsService.recordDocumentUpload(task.knowledgeSpaceId, segmentCount);
            status.setSegmentCount(segmentCount);
            status.setStatus("COMPLETED");
        } catch (Exception error) {
            markFailed(task, error.getMessage());
            log.error("Document ingestion failed | taskId={}, error={}", task.taskId, error.getMessage(), error);
        } finally {
            status.setEndTime(System.currentTimeMillis());
            activeTaskIds.remove(task.taskId);
            deleteStaged(task.stagingPath);
        }
    }

    private void publishDlq(String payload, Exception failure) {
        try {
            KafkaTemplate<String, String> template = kafkaTemplateProvider.getIfAvailable();
            if (template == null) return;
            String envelope = objectMapper.writeValueAsString(Map.of(
                    "payload", payload == null ? "" : payload,
                    "error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    "failedAt", System.currentTimeMillis()));
            template.send(properties.dlqTopic(), envelope);
        } catch (Exception dlqError) {
            log.error("Document ingest DLQ publish failed | error={}", dlqError.getMessage());
        }
    }

    private void markFailed(DocumentIngestTask task, String error) {
        ProcessTask status = taskStatusMap.computeIfAbsent(task.taskId,
                id -> new ProcessTask(id, task.filename, task.knowledgeSpaceId));
        status.setStatus("FAILED");
        status.setErrorMessage(error == null ? "Document ingestion failed" : error);
        status.setEndTime(System.currentTimeMillis());
    }

    private void deleteStaged(String path) {
        try {
            Path file = Path.of(path);
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
        } catch (Exception error) {
            log.warn("Cannot delete staged document | path={}, error={}", path, error.getMessage());
        }
    }

    private void maybeCleanupTasks() {
        if (taskAccessCounter.incrementAndGet() % TASK_CLEANUP_INTERVAL == 0) {
            cleanupOldTasks(TASK_MAX_AGE_MS);
            cleanupStagingArtifacts(Duration.ofHours(properties.stagingMaxAgeHours()).toMillis());
        }
    }

    /** Removes crash leftovers without touching recent or currently executing uploads. */
    void cleanupStagingArtifacts(long maxAgeMs) {
        Path root = properties.stagingDirectory().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;
        long cutoff = System.currentTimeMillis() - Math.max(1, maxAgeMs);
        try (var paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException ignored) {
                            // A non-empty or active directory is intentionally retained.
                        }
                    });
        } catch (IOException error) {
            log.warn("Cannot clean document staging directory | path={}, error={}", root, error.getMessage());
        }
    }

    private String requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{8,80}")) {
            throw new IllegalArgumentException("Invalid document task id.");
        }
        return taskId;
    }

    private String safeFilename(String value) {
        String filename = value == null || value.isBlank() ? "document.bin" : Path.of(value).getFileName().toString();
        String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "document.bin" : safe;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public static class ProcessTask {
        private String taskId;
        private String filename;
        private String researchGroupId;
        private String status = "PENDING";
        private String queueMode = "pending";
        private long startTime = System.currentTimeMillis();
        private Long endTime;
        private int segmentCount;
        private String errorMessage;

        public ProcessTask(String taskId, String filename, String researchGroupId) {
            this.taskId = taskId;
            this.filename = filename;
            this.researchGroupId = researchGroupId;
        }

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getResearchGroupId() { return researchGroupId; }
        public void setResearchGroupId(String researchGroupId) { this.researchGroupId = researchGroupId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getQueueMode() { return queueMode; }
        public void setQueueMode(String queueMode) { this.queueMode = queueMode; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public Long getEndTime() { return endTime; }
        public void setEndTime(Long endTime) { this.endTime = endTime; }
        public int getSegmentCount() { return segmentCount; }
        public void setSegmentCount(int segmentCount) { this.segmentCount = segmentCount; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public Long getDurationMs() { return (endTime == null ? System.currentTimeMillis() : endTime) - startTime; }
    }
}
