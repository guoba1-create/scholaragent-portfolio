package com.team.rag.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.runtime.MemoryRecallRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextCompilerServiceTest {

    private ContextCompilerService compiler;

    @BeforeEach
    void setUp() {
        ContextSemanticCompressionService semantic = mock(ContextSemanticCompressionService.class);
        when(semantic.compress(any(), any(), anyInt()))
                .thenAnswer(invocation -> new ContextSemanticCompressionService.CompressionResult(
                        invocation.getArgument(1), false, null, false));
        compiler = new ContextCompilerService(
                new TokenEstimator(),
                new EvidenceIntegrityValidator(),
                semantic,
                new ObjectMapper(),
                800,
                1200,
                1600,
                0.75);
    }

    @Test
    void compilesWithinBudgetAndPreservesEvidenceIdentity() {
        ContextCompileRequest request = new ContextCompileRequest();
        request.workflowId = "wf-test";
        request.strategy = "quick_qa";
        request.userQuery = "STRNN 的核心思想是什么？";
        request.workingMemory = "重复工作记忆 ".repeat(3000);
        request.maxPromptTokens = 700;
        request.evidenceRefs = List.of(evidence("E1", "doc-1", "chunk-2", "证据原文 ".repeat(900)));

        ContextEnvelope envelope = compiler.compile(request);

        assertThat(envelope.stats.finalTokens).isLessThanOrEqualTo(700);
        assertThat(envelope.stats.citationsPreserved).isTrue();
        assertThat(envelope.evidenceRefs).extracting(item -> item.citationId).containsExactly("E1");
        assertThat(envelope.promptContext).contains("[E1]", "documentId=doc-1", "chunkId=chunk-2");
        assertThat(envelope.compressionRecords).isNotEmpty();
    }

    @Test
    void deduplicatesSemanticMemoryByStableSourceId() {
        MemoryRecallRecord recall = new MemoryRecallRecord();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("memoryId", "mem-1");
        first.put("content", "用户关注 next-POI 推荐");
        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("memoryId", "mem-1");
        duplicate.put("content", "用户关注 next-POI 推荐");
        recall.semanticMemories = new ArrayList<>(List.of(first, duplicate));

        ContextCompileRequest request = new ContextCompileRequest();
        request.userQuery = "继续分析";
        request.memoryRecall = recall;

        ContextEnvelope envelope = compiler.compile(request);

        assertThat(envelope.segments.stream()
                .filter(item -> item.type == ContextSegmentType.LONG_TERM_MEMORY))
                .hasSize(1);
        assertThat(envelope.compressionRecords)
                .anyMatch(item -> "L0_DEDUPLICATE".equals(item.stage));
    }

    @Test
    void failsClosedWhenRequiredEvidenceCannotFitHardBudget() {
        ContextCompileRequest request = new ContextCompileRequest();
        request.userQuery = "核验";
        request.maxPromptTokens = 256;
        request.allowModelCompression = false;
        request.evidenceRefs = List.of(
                evidence("E1", "doc-1", "chunk-1", "a".repeat(3000)),
                evidence("E2", "doc-2", "chunk-2", "b".repeat(3000)),
                evidence("E3", "doc-3", "chunk-3", "c".repeat(3000)));

        assertThatThrownBy(() -> compiler.compile(request))
                .isInstanceOf(ContextBudgetExceededException.class)
                .hasMessageContaining("hard token budget");
    }

    private EvidenceReference evidence(String citationId, String documentId,
                                       String chunkId, String quote) {
        EvidenceReference reference = new EvidenceReference();
        reference.citationId = citationId;
        reference.sourceType = "LOCAL_PAPER";
        reference.documentId = documentId;
        reference.chunkId = chunkId;
        reference.title = "STRNN paper";
        reference.section = "Method";
        reference.quote = quote;
        return reference;
    }
}
