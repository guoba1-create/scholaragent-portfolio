package com.team.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.queue.DocumentIngestionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncDocumentProcessServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void stagesRequestBytesAndCompletesThroughBoundedLocalFallback() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.uploadAndProcessDocument(any(byte[].class), eq("paper.pdf"), eq("space-a")))
                .thenReturn(7);
        StatsService stats = mock(StatsService.class);
        DocumentIngestionProperties properties = properties(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<String, String>> kafkaProvider = mock(ObjectProvider.class);
        AsyncDocumentProcessService service = new AsyncDocumentProcessService(
                documentService, stats, properties, new ObjectMapper().findAndRegisterModules(), kafkaProvider);

        try {
            service.processDocumentAsync("task-1234",
                    new MockMultipartFile("file", "paper.pdf", "application/pdf", "pdf-content".getBytes()),
                    "space-a", "paper.pdf");

            AsyncDocumentProcessService.ProcessTask task = awaitTerminal(service, "task-1234");
            assertThat(task.getStatus()).isEqualTo("COMPLETED");
            assertThat(task.getQueueMode()).isEqualTo("local-fallback");
            assertThat(task.getSegmentCount()).isEqualTo(7);
            verify(documentService).uploadAndProcessDocument(any(byte[].class), eq("paper.pdf"), eq("space-a"));
            verify(stats).recordDocumentUpload("space-a", 7);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void rejectsUploadAboveConfiguredLimitBeforeQueueing() {
        DocumentIngestionProperties properties = properties(false);
        ReflectionTestUtils.setField(properties, "maxFileBytes", 4L);
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<String, String>> kafkaProvider = mock(ObjectProvider.class);
        AsyncDocumentProcessService service = new AsyncDocumentProcessService(
                mock(DocumentService.class), mock(StatsService.class), properties,
                new ObjectMapper(), kafkaProvider);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.processDocumentAsync(
                    "task-5678",
                    new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[8]),
                    "space-a", "large.pdf"))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("upload limit");
        } finally {
            service.shutdown();
        }
    }

    private DocumentIngestionProperties properties(boolean kafkaEnabled) {
        DocumentIngestionProperties properties = new DocumentIngestionProperties();
        ReflectionTestUtils.setField(properties, "kafkaEnabled", kafkaEnabled);
        ReflectionTestUtils.setField(properties, "topic", "document.ingest");
        ReflectionTestUtils.setField(properties, "dlqTopic", "document.ingest.dlq");
        ReflectionTestUtils.setField(properties, "stagingDirectory", tempDirectory.toString());
        ReflectionTestUtils.setField(properties, "localThreads", 1);
        ReflectionTestUtils.setField(properties, "localQueueCapacity", 10);
        ReflectionTestUtils.setField(properties, "maxFileBytes", 1024L);
        return properties;
    }

    private AsyncDocumentProcessService.ProcessTask awaitTerminal(
            AsyncDocumentProcessService service, String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        AsyncDocumentProcessService.ProcessTask task;
        do {
            task = service.getTaskStatus(taskId);
            if (task != null && ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus()))) {
                return task;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return service.getTaskStatus(taskId);
    }
}
