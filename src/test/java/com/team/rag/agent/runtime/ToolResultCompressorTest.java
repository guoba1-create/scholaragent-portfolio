package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.context.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultCompressorTest {

    private final ToolResultCompressor compressor =
            new ToolResultCompressor(new ObjectMapper(), new TokenEstimator());

    @Test
    void boundsPayloadAndPreservesFiveStructuredCitations() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documentId", "doc-" + i);
            row.put("chunkId", "chunk-" + i);
            row.put("title", "Paper " + i);
            row.put("section", "Method");
            row.put("pageNumber", i + 1);
            row.put("chunkIndex", i);
            row.put("score", 0.9 - i * 0.01);
            row.put("content", "evidence ".repeat(200));
            rows.add(row);
        }

        ToolObservation result = compressor.compress(
                "wf-1", "step-1", "retrieve-evidence", "success",
                Map.of("evidenceSpans", rows), 25);

        assertThat(result.toolName).isEqualTo("retrieve_evidence");
        assertThat(result.status).isEqualTo("SUCCESS");
        assertThat(result.facts).hasSize(5);
        assertThat(result.citations).hasSize(5);
        assertThat(result.citations.get(0)).containsEntry("citationId", "E1")
                .containsEntry("documentId", "doc-0")
                .containsEntry("chunkId", "chunk-0");
        assertThat(result.compressedText.length())
                .isLessThanOrEqualTo(ToolResultCompressor.MAX_COMPRESSED_CHARS);
        assertThat(result.rawPayloadRef).startsWith("payload:sha256:");
        assertThat(result.rawTokenCount).isGreaterThan(result.compressedTokenCount);
    }

    @Test
    void retainsBoundedFailureDetailsWithoutInventingEvidence() {
        ToolObservation result = compressor.compress(
                "wf-2", "step-2", "web-search", "failed",
                Map.of("error", "timeout", "details", "x".repeat(25_000)), 1_500);

        assertThat(result.status).isEqualTo("FAILED");
        assertThat(result.evidence).isEmpty();
        assertThat(result.rawPayloadTruncated).isTrue();
        assertThat(result.rawPayloadPreview.length()).isLessThanOrEqualTo(20_000);
        assertThat(result.summary).contains("failed");
    }
}
