package com.team.rag.agent.runtime;

import com.team.rag.agent.context.ContextCompileRequest;
import com.team.rag.agent.context.ContextCompilerService;
import com.team.rag.agent.context.ContextEnvelope;
import com.team.rag.agent.retrieval.RetrievalContext;
import com.team.rag.agent.retrieval.RetrievalContextMapper;
import com.team.rag.agent.routing.HybridRouteDecision;
import com.team.rag.entity.DocumentChunkRef;
import com.team.rag.service.LiteratureDialogueService;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Executes the single-turn Java quick-answer strategy with one reusable retrieval context. */
@Service
public class QuickQaExecutionService {

    private final LiteratureDialogueService dialogueService;
    private final ContextCompilerService contextCompiler;
    private final RetrievalContextMapper retrievalMapper;
    private final WorkflowEventBus eventBus;
    private final WorkflowSnapshotStore snapshotStore;

    public QuickQaExecutionService(
            LiteratureDialogueService dialogueService,
            ContextCompilerService contextCompiler,
            RetrievalContextMapper retrievalMapper,
            WorkflowEventBus eventBus,
            WorkflowSnapshotStore snapshotStore) {
        this.dialogueService = dialogueService;
        this.contextCompiler = contextCompiler;
        this.retrievalMapper = retrievalMapper;
        this.eventBus = eventBus;
        this.snapshotStore = snapshotStore;
    }

    public String execute(
            String workflowId,
            String userId,
            String knowledgeSpaceId,
            String conversationId,
            String message,
            MemoryRecallRecord recall,
            boolean chitchat,
            WorkflowRunSnapshot snapshot,
            HybridRouteDecision routeDecision) {
        StringBuilder answer = new StringBuilder();
        if (chitchat) {
            dialogueService.streamGeneralChat(userId, conversationId, message)
                    .doOnNext(answer::append)
                    .blockLast();
        } else {
            dialogueService.streamRagOnly(userId, knowledgeSpaceId, conversationId, message, recall,
                            retrieval -> compileRetrievalContext(
                                    workflowId, message, recall, snapshot, routeDecision, retrieval))
                    .doOnNext(answer::append)
                    .blockLast();
        }
        return answer.toString();
    }

    private String compileRetrievalContext(
            String workflowId,
            String message,
            MemoryRecallRecord recall,
            WorkflowRunSnapshot snapshot,
            HybridRouteDecision routeDecision,
            RetrievalContext retrieval) {
        retrieval.workflowId = workflowId;
        snapshot.retrievalContext = retrieval;
        snapshot.documentRefs = retrievalMapper.toEvidenceReferences(retrieval).stream()
                .map(ref -> {
                    DocumentChunkRef value = new DocumentChunkRef();
                    value.documentId = ref.documentId;
                    value.researchGroupId = retrieval.knowledgeSpaceId;
                    value.title = ref.title;
                    value.section = ref.section;
                    value.chunkId = ref.chunkId;
                    value.pageNumber = ref.pageNumber;
                    value.pageStart = ref.pageNumber;
                    value.pageEnd = ref.pageNumber;
                    value.chunkIndex = ref.chunkIndex;
                    value.score = ref.score;
                    return value;
                })
                .toList();

        ContextCompileRequest request = new ContextCompileRequest();
        request.workflowId = workflowId;
        request.strategy = snapshot.executionDecision == null
                ? defaultString(routeDecision == null ? null : routeDecision.engine, "rag")
                : snapshot.executionDecision.strategy.name();
        request.systemPolicy = "Answer from the supplied research evidence. Preserve citation ids and state uncertainty.";
        request.userQuery = message;
        request.memoryRecall = recall;
        request.evidenceRefs = retrievalMapper.toEvidenceReferences(retrieval);
        request.diagnostics = retrieval.diagnostics;
        ContextEnvelope envelope = contextCompiler.compile(request);
        snapshot.contextEnvelope = envelope;
        snapshot.contextStats = envelope.stats;
        snapshot.contextBudget = legacyBudget(routeDecision, envelope);
        recall.compactPromptContext = envelope.promptContext;

        eventBus.publish(workflowId, "RETRIEVAL_READY", "retrieval", "RetrievalPipeline", null,
                "SUCCESS", "Structured retrieval context is ready", Map.of(
                        "evidenceCount", retrieval.hits.size(),
                        "memoryHitCount", retrieval.memoryHits.size(),
                        "cragDecision", defaultString(retrieval.cragDecision, "UNKNOWN"),
                        "latencyMs", retrieval.retrievalLatencyMs,
                        "contextTokens", envelope.stats.finalTokens));
        snapshotStore.save(snapshot);
        return envelope.promptContext;
    }

    private ContextBudgetSnapshot legacyBudget(HybridRouteDecision routeDecision, ContextEnvelope envelope) {
        ContextBudgetSnapshot budget = new ContextBudgetSnapshot();
        budget.engine = routeDecision == null ? "rag" : routeDecision.engine;
        budget.profile = routeDecision == null ? "quick_qa" : routeDecision.profile;
        budget.maxPromptChars = envelope.budget.maxPromptTokens * 4;
        budget.totalPromptChars = envelope.promptContext == null ? 0 : envelope.promptContext.length();
        budget.promptContext = envelope.promptContext;
        budget.truncated = !envelope.compressionRecords.isEmpty();
        budget.truncatedSections = envelope.stats.droppedSegments;
        budget.diagnostics = envelope.stats.warnings;
        return budget;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
