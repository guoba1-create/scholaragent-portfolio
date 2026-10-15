package com.team.rag.agent.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContextEnvelope {
    public String contextId;
    public String schemaVersion = "2.0";
    public String workflowId;
    public String strategy;
    public List<ContextSegment> segments = new ArrayList<>();
    public ContextBudgetPolicy budget;
    public List<CompressionRecord> compressionRecords = new ArrayList<>();
    public List<EvidenceReference> evidenceRefs = new ArrayList<>();
    public ContextStats stats = new ContextStats();
    public String promptContext;
    public String fingerprint;
    public Instant createdAt = Instant.now();
}
