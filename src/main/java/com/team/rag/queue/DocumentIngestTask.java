package com.team.rag.queue;

import java.time.Instant;

/** Durable reference passed through Kafka; file bytes stay in controlled staging storage. */
public class DocumentIngestTask {
    public String taskId;
    public String filename;
    public String knowledgeSpaceId;
    public String stagingPath;
    public String contentHash;
    public long sizeBytes;
    public Instant createdAt = Instant.now();

    public DocumentIngestTask() {
    }
}
