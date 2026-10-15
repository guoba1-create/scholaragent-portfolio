package com.team.rag.agent.retrieval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.rag.entity.RetrievalHit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The single structured retrieval result for one workflow execution.
 *
 * <p>The answer generator, context compiler, diagnostics and UI all consume
 * this object. They must not issue another retrieval for the same query.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetrievalContext {

    public String workflowId;
    public String query;
    public List<String> rewrittenQueries = new ArrayList<>();
    public String knowledgeSpaceId;
    public List<RetrievalHit> hits = new ArrayList<>();
    public List<RetrievalHit> memoryHits = new ArrayList<>();
    public String cragDecision;
    public String cragReason;
    public List<String> diagnostics = new ArrayList<>();
    public String retrievalVersion = "hybrid-rag-v2";
    public long retrievalLatencyMs;
    public Instant createdAt = Instant.now();

    public boolean hasEvidence() {
        return hits != null && !hits.isEmpty();
    }
}
