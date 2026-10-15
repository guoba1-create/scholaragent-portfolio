package com.team.rag.agent.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompressionRecord {
    public String stage;
    public ContextSegmentType segmentType;
    public int beforeTokens;
    public int afterTokens;
    public int droppedItems;
    public int citationsBefore;
    public int citationsAfter;
    public String modelUsed;
    public boolean cacheHit;
    public String reason;
    public Instant createdAt = Instant.now();
}
