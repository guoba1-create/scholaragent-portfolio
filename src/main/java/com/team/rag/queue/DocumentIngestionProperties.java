package com.team.rag.queue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class DocumentIngestionProperties {

    @Value("${document.ingest.kafka.enabled:true}")
    private boolean kafkaEnabled;
    @Value("${document.ingest.kafka.topic:document.ingest}")
    private String topic;
    @Value("${document.ingest.kafka.dlq-topic:document.ingest.dlq}")
    private String dlqTopic;
    @Value("${document.ingest.staging-directory:data/ingest-queue}")
    private String stagingDirectory;
    @Value("${document.ingest.local-threads:2}")
    private int localThreads;
    @Value("${document.ingest.local-queue-capacity:100}")
    private int localQueueCapacity;
    @Value("${document.ingest.max-file-bytes:52428800}")
    private long maxFileBytes;
    @Value("${document.ingest.staging-max-age-hours:24}")
    private long stagingMaxAgeHours;

    public boolean kafkaEnabled() { return kafkaEnabled; }
    public String topic() { return value(topic, "document.ingest"); }
    public String dlqTopic() { return value(dlqTopic, "document.ingest.dlq"); }
    public Path stagingDirectory() { return Path.of(value(stagingDirectory, "data/ingest-queue")); }
    public int localThreads() { return Math.max(1, Math.min(4, localThreads)); }
    public int localQueueCapacity() { return Math.max(10, Math.min(1_000, localQueueCapacity)); }
    public long maxFileBytes() { return Math.max(1, maxFileBytes); }
    public long stagingMaxAgeHours() { return Math.max(1, stagingMaxAgeHours); }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
