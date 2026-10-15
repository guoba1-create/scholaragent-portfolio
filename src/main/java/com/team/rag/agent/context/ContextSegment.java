package com.team.rag.agent.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContextSegment {
    public String segmentId;
    public ContextSegmentType type;
    public int priority;
    public String content;
    public int tokenCount;
    public String sourceRef;
    public boolean compressible = true;
    public List<String> immutableFields = new ArrayList<>();
    public Instant expiresAt;

    public ContextSegment copy() {
        ContextSegment copy = new ContextSegment();
        copy.segmentId = segmentId;
        copy.type = type;
        copy.priority = priority;
        copy.content = content;
        copy.tokenCount = tokenCount;
        copy.sourceRef = sourceRef;
        copy.compressible = compressible;
        copy.immutableFields = new ArrayList<>(immutableFields == null ? List.of() : immutableFields);
        copy.expiresAt = expiresAt;
        return copy;
    }
}
