package com.team.rag.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.context.TokenEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministically converts an arbitrary tool payload into a bounded,
 * citation-preserving observation. It never asks an LLM to summarize facts.
 */
@Service
public class ToolResultCompressor {

    static final int MAX_COMPRESSED_CHARS = 1800;
    static final int MAX_RAW_PREVIEW_CHARS = 20_000;
    static final int MAX_EVIDENCE = 5;

    private static final List<String> FACT_KEYS = List.of(
            "documentId", "title", "section", "sectionPath", "pageNumber",
            "pageStart", "pageEnd", "chunkIndex", "chunkId", "score",
            "status", "reason", "url", "source", "text", "content");

    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator;
    private RawToolPayloadStore rawPayloadStore;

    public ToolResultCompressor(ObjectMapper objectMapper, TokenEstimator tokenEstimator) {
        this.objectMapper = objectMapper;
        this.tokenEstimator = tokenEstimator;
    }

    @Autowired(required = false)
    void setRawPayloadStore(RawToolPayloadStore rawPayloadStore) {
        this.rawPayloadStore = rawPayloadStore;
    }

    public ToolObservation compress(String workflowId, String stepId, String toolName,
                                    String status, Object rawPayload, long latencyMs) {
        ToolObservation result = new ToolObservation();
        result.observationId = "obs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        result.workflowId = workflowId;
        result.stepId = stepId;
        result.toolName = normalizeToolName(toolName);
        result.status = normalizeStatus(status);
        result.latencyMs = Math.max(0, latencyMs);
        result.createdAt = Instant.now();

        String raw = serialize(rawPayload);
        result.rawPayloadRef = rawPayloadStore == null
                ? "payload:sha256:" + sha256(raw)
                : rawPayloadStore.store(workflowId, result.toolName, rawPayload);
        result.rawPayloadPreview = truncate(raw, MAX_RAW_PREVIEW_CHARS);
        result.rawPayloadTruncated = raw.length() > MAX_RAW_PREVIEW_CHARS;
        result.rawTokenCount = tokenEstimator.estimate(raw);

        Object normalized = normalize(rawPayload, raw);
        collectFacts(normalized, result.facts, result.citations, result.evidence);
        result.compressedText = buildCompressedText(result, raw);
        result.compressedTokenCount = tokenEstimator.estimate(result.compressedText);
        result.summary = summarize(result);
        if (result.rawPayloadTruncated) {
            result.warnings.add("Raw payload preview was truncated; use rawPayloadRef for audit lookup.");
        }
        if (result.facts.isEmpty() && "SUCCESS".equals(result.status)) {
            result.warnings.add("Tool returned no structured facts; bounded text fallback was retained.");
        }
        return result;
    }

    public ToolObservation normalizeExisting(String workflowId, ToolObservation input) {
        if (input == null) {
            return null;
        }
        String payload = firstNonBlank(input.rawPayloadPreview, input.compressedText, input.summary, "");
        ToolObservation normalized = compress(
                workflowId,
                input.stepId,
                input.toolName,
                input.status,
                payload,
                input.latencyMs);
        if (input.observationId != null && !input.observationId.isBlank()) {
            normalized.observationId = input.observationId;
        }
        if (input.rawPayloadRef != null && !input.rawPayloadRef.isBlank()) {
            normalized.rawPayloadRef = input.rawPayloadRef;
        }
        if (input.facts != null && !input.facts.isEmpty()) {
            normalized.facts = limitMaps(input.facts, MAX_EVIDENCE);
        }
        if (input.citations != null && !input.citations.isEmpty()) {
            normalized.citations = limitMaps(input.citations, MAX_EVIDENCE);
        }
        if (input.evidence != null && !input.evidence.isEmpty()) {
            normalized.evidence = limitMaps(input.evidence, MAX_EVIDENCE);
        }
        if (input.warnings != null) {
            normalized.warnings.addAll(input.warnings.stream().limit(10).toList());
        }
        normalized.compressedText = truncate(
                firstNonBlank(input.compressedText, normalized.compressedText), MAX_COMPRESSED_CHARS);
        normalized.compressedTokenCount = tokenEstimator.estimate(normalized.compressedText);
        return normalized;
    }

    private void collectFacts(Object value, List<Map<String, Object>> facts,
                              List<Map<String, Object>> citations,
                              List<Map<String, Object>> evidence) {
        if (value == null || facts.size() >= MAX_EVIDENCE) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> fact = extractFact(map);
            if (isEvidenceFact(fact)) {
                facts.add(fact);
                Map<String, Object> citation = citationOf(fact, facts.size());
                citations.add(citation);
                evidence.add(new LinkedHashMap<>(fact));
            }
            for (Object child : map.values()) {
                collectFacts(child, facts, citations, evidence);
                if (facts.size() >= MAX_EVIDENCE) {
                    return;
                }
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                collectFacts(child, facts, citations, evidence);
                if (facts.size() >= MAX_EVIDENCE) {
                    return;
                }
            }
        }
    }

    private Map<String, Object> extractFact(Map<?, ?> source) {
        Map<String, Object> fact = new LinkedHashMap<>();
        for (String key : FACT_KEYS) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                fact.put(key, compactValue(value));
            }
        }
        return fact;
    }

    private boolean isEvidenceFact(Map<String, Object> fact) {
        return fact.containsKey("documentId") || fact.containsKey("url")
                || fact.containsKey("content") || fact.containsKey("text")
                || fact.containsKey("title");
    }

    private Map<String, Object> citationOf(Map<String, Object> fact, int index) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("citationId", "E" + index);
        for (String key : List.of("documentId", "chunkId", "chunkIndex", "title",
                "section", "sectionPath", "pageNumber", "pageStart", "pageEnd", "url", "score")) {
            if (fact.containsKey(key)) {
                citation.put(key, fact.get(key));
            }
        }
        return citation;
    }

    private String buildCompressedText(ToolObservation observation, String raw) {
        StringBuilder text = new StringBuilder();
        text.append("tool=").append(observation.toolName)
                .append(" status=").append(observation.status);
        int index = 1;
        for (Map<String, Object> fact : observation.facts) {
            text.append("\n[E").append(index++).append("] ");
            append(text, fact, "title");
            append(text, fact, "sectionPath");
            append(text, fact, "section");
            append(text, fact, "documentId");
            append(text, fact, "pageNumber");
            append(text, fact, "chunkIndex");
            append(text, fact, "score");
            append(text, fact, fact.containsKey("content") ? "content" : "text");
        }
        if (observation.facts.isEmpty()) {
            text.append("\nresult=").append(raw);
        }
        return truncate(text.toString(), MAX_COMPRESSED_CHARS);
    }

    private void append(StringBuilder target, Map<String, Object> fact, String key) {
        Object value = fact.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.append(key).append('=').append(value).append("; ");
        }
    }

    private String summarize(ToolObservation observation) {
        if (!"SUCCESS".equals(observation.status)) {
            return "Tool call failed; bounded failure details retained.";
        }
        if (!observation.evidence.isEmpty()) {
            return "Tool returned " + observation.evidence.size() + " structured evidence item(s).";
        }
        return "Tool call completed with no structured evidence.";
    }

    private Object normalize(Object rawPayload, String raw) {
        if (rawPayload instanceof Map<?, ?> || rawPayload instanceof Iterable<?>) {
            return rawPayload;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Object>() { });
        } catch (Exception ignored) {
            return rawPayload;
        }
    }

    private Object compactValue(Object value) {
        String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() > 600 ? text.substring(0, 600) + "..." : value instanceof Number ? value : text;
    }

    private String serialize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private List<Map<String, Object>> limitMaps(List<Map<String, Object>> values, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> value : values.stream().limit(limit).toList()) {
            result.add(new LinkedHashMap<>(value));
        }
        return result;
    }

    private String normalizeStatus(String value) {
        return value != null && value.equalsIgnoreCase("SUCCESS") ? "SUCCESS" : "FAILED";
    }

    private String normalizeToolName(String value) {
        return firstNonBlank(value, "unknown_tool").trim().toLowerCase().replace('-', '_');
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
