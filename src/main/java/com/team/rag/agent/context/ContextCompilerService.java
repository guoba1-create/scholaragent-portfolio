package com.team.rag.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.runtime.MemoryRecallRecord;
import com.team.rag.agent.runtime.ToolObservation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.team.rag.monitoring.AgentBusinessMetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Compiles heterogeneous conversation, memory, evidence and tool data into a
 * budgeted, auditable prompt context. All execution strategies use this service.
 */
@Service
public class ContextCompilerService {

    private final TokenEstimator tokenEstimator;
    private final EvidenceIntegrityValidator evidenceValidator;
    private final ContextSemanticCompressionService semanticCompressionService;
    private final ObjectMapper objectMapper;
    private final int quickQaMaxTokens;
    private final int workflowMaxTokens;
    private final int deepResearchMaxTokens;
    private final double softLimitRatio;
    private AgentBusinessMetrics metrics;
    private ModelCapabilityRegistry modelCapabilityRegistry;

    public ContextCompilerService(
            TokenEstimator tokenEstimator,
            EvidenceIntegrityValidator evidenceValidator,
            ContextSemanticCompressionService semanticCompressionService,
            ObjectMapper objectMapper,
            @Value("${context.quick-qa-max-prompt-tokens:8192}") int quickQaMaxTokens,
            @Value("${context.workflow-max-prompt-tokens:16384}") int workflowMaxTokens,
            @Value("${context.deep-research-max-prompt-tokens:24576}") int deepResearchMaxTokens,
            @Value("${context.soft-limit-ratio:0.75}") double softLimitRatio) {
        this.tokenEstimator = tokenEstimator;
        this.evidenceValidator = evidenceValidator;
        this.semanticCompressionService = semanticCompressionService;
        this.objectMapper = objectMapper;
        this.quickQaMaxTokens = positive(quickQaMaxTokens, 8192);
        this.workflowMaxTokens = positive(workflowMaxTokens, 16384);
        this.deepResearchMaxTokens = positive(deepResearchMaxTokens, 24576);
        this.softLimitRatio = softLimitRatio > 0.4 && softLimitRatio < 1.0 ? softLimitRatio : 0.75;
    }

    @Autowired(required = false)
    void setMetrics(AgentBusinessMetrics metrics) {
        this.metrics = metrics;
    }

    @Autowired(required = false)
    void setModelCapabilityRegistry(ModelCapabilityRegistry modelCapabilityRegistry) {
        this.modelCapabilityRegistry = modelCapabilityRegistry;
    }

    public ContextEnvelope compile(ContextCompileRequest request) {
        ContextCompileRequest safe = request == null ? new ContextCompileRequest() : request;
        String strategy = normalizeStrategy(safe.strategy);

        ContextEnvelope envelope = new ContextEnvelope();
        envelope.contextId = "ctx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        envelope.workflowId = safe.workflowId;
        envelope.strategy = strategy;
        envelope.evidenceRefs = copyEvidence(safe.evidenceRefs);
        envelope.budget = budget(strategy, safe.maxPromptTokens, safe.maxOutputTokens);

        List<ContextSegment> collected = collect(safe);
        List<ContextSegment> normalized = normalizeAndDeduplicate(collected, envelope.compressionRecords);
        refreshTokens(normalized);

        envelope.stats.originalSegments = normalized.size();
        envelope.stats.originalTokens = totalTokens(normalized);
        envelope.stats.citationsBefore = envelope.evidenceRefs.size();

        if (envelope.stats.originalTokens > envelope.budget.softLimitTokens) {
            envelope.budget.softLimitExceeded = true;
            deterministicCompression(normalized, envelope);
        }
        if (totalTokens(normalized) > envelope.budget.maxPromptTokens && safe.allowModelCompression) {
            semanticCompression(normalized, envelope);
        }
        if (totalTokens(normalized) > envelope.budget.maxPromptTokens) {
            dropLowPrioritySegments(normalized, envelope);
        }

        refreshTokens(normalized);
        int finalTokens = totalTokens(normalized);
        if (finalTokens > envelope.budget.maxPromptTokens) {
            envelope.budget.hardLimitExceeded = true;
            throw new ContextBudgetExceededException(
                    "Context cannot fit the hard token budget without losing required query/evidence metadata. "
                            + "tokens=" + finalTokens + ", max=" + envelope.budget.maxPromptTokens);
        }

        envelope.segments = normalized.stream()
                .sorted(Comparator.comparingInt((ContextSegment item) -> item.priority).reversed())
                .toList();
        envelope.promptContext = renderPrompt(envelope.segments);
        envelope.fingerprint = fingerprint(envelope.segments);

        envelope.budget.usedPromptTokens = tokenEstimator.estimate(envelope.promptContext);
        envelope.budget.remainingPromptTokens = Math.max(
                0, envelope.budget.maxPromptTokens - envelope.budget.usedPromptTokens);
        envelope.stats.finalSegments = envelope.segments.size();
        envelope.stats.finalTokens = envelope.budget.usedPromptTokens;
        envelope.stats.compressionRatio = envelope.stats.originalTokens == 0
                ? 1.0
                : round((double) envelope.stats.finalTokens / envelope.stats.originalTokens);
        envelope.stats.citationsAfter = envelope.evidenceRefs.size();

        EvidenceIntegrityValidator.ValidationResult validation = evidenceValidator.validate(
                safe.evidenceRefs, envelope.evidenceRefs);
        envelope.stats.citationsPreserved = validation.valid();
        if (!validation.valid()) {
            envelope.stats.warnings.add("Evidence identity changed during context compilation.");
            throw new IllegalStateException("Evidence integrity validation failed: missing=" + validation.missing());
        }
        for (ContextSegment segment : envelope.segments) {
            envelope.stats.tokensByType.merge(segment.type.name(), segment.tokenCount, Integer::sum);
        }
        if (metrics != null) {
            metrics.contextCompiled(envelope);
        }
        return envelope;
    }

    private List<ContextSegment> collect(ContextCompileRequest request) {
        List<ContextSegment> result = new ArrayList<>();
        add(result, ContextSegmentType.SYSTEM_POLICY, 100, request.systemPolicy, "system", false);
        add(result, ContextSegmentType.USER_QUERY, 100, request.userQuery, "query", false);
        int conversationIndex = 0;
        for (String message : safeList(request.recentConversation)) {
            add(result, ContextSegmentType.RECENT_CONVERSATION, 72, message,
                    "conversation:" + conversationIndex++, true);
        }
        add(result, ContextSegmentType.CONVERSATION_SUMMARY, 76,
                request.conversationSummary, "conversation-summary", true);
        collectRecall(result, request.memoryRecall);
        collectEvidence(result, request.evidenceRefs);
        add(result, ContextSegmentType.PLAN_STATE, 84, request.planState, "plan-state", true);
        add(result, ContextSegmentType.WORKING_MEMORY, 82, request.workingMemory, "working-memory", true);
        int observationIndex = 0;
        for (ToolObservation observation : safeList(request.toolObservations)) {
            if (observation == null) {
                continue;
            }
            String content = firstNonBlank(observation.compressedText, observation.summary, observation.rawPayloadPreview);
            add(result, ContextSegmentType.TOOL_OBSERVATION, 78, content,
                    firstNonBlank(observation.observationId, "tool-observation:" + observationIndex++), true);
        }
        int diagnosticIndex = 0;
        for (String diagnostic : safeList(request.diagnostics)) {
            add(result, ContextSegmentType.DIAGNOSTICS, 35, diagnostic,
                    "diagnostic:" + diagnosticIndex++, true);
        }
        return result;
    }

    private void collectRecall(List<ContextSegment> segments, MemoryRecallRecord recall) {
        if (recall == null) {
            return;
        }
        add(segments, ContextSegmentType.CONVERSATION_SUMMARY, 76,
                recall.shortTermSummary, firstNonBlank(recall.memoryId, "recall-summary"), true);
        int memoryIndex = 0;
        for (Map<String, Object> memory : safeList(recall.semanticMemories)) {
            String content = value(memory, "content");
            add(segments, ContextSegmentType.LONG_TERM_MEMORY, 62, content,
                    firstNonBlank(value(memory, "memoryId"), "semantic-memory:" + memoryIndex++), true);
        }
        if (recall.projectContext != null && !recall.projectContext.isEmpty()) {
            add(segments, ContextSegmentType.KNOWLEDGE_SPACE_SUMMARY, 55,
                    renderProjectContext(recall.projectContext), "knowledge-space", true);
        }
        int diagnosticIndex = 0;
        for (String diagnostic : safeList(recall.diagnostics)) {
            add(segments, ContextSegmentType.DIAGNOSTICS, 35, diagnostic,
                    "recall-diagnostic:" + diagnosticIndex++, true);
        }
    }

    private void collectEvidence(List<ContextSegment> segments, List<EvidenceReference> evidenceRefs) {
        for (EvidenceReference ref : safeList(evidenceRefs)) {
            if (ref == null) {
                continue;
            }
            ContextSegmentType type = "EXTERNAL".equalsIgnoreCase(ref.sourceType)
                    || "EXTERNAL_PAPER".equalsIgnoreCase(ref.sourceType)
                    || "WEB".equalsIgnoreCase(ref.sourceType)
                    ? ContextSegmentType.EXTERNAL_EVIDENCE
                    : ContextSegmentType.RETRIEVAL_EVIDENCE;
            String content = renderEvidence(ref);
            ContextSegment segment = segment(type, 96, content,
                    "evidence:" + firstNonBlank(ref.citationId, ref.stableIdentity()), true);
            segment.immutableFields = List.of(
                    "citationId", "sourceType", "documentId", "chunkId", "title", "url");
            segments.add(segment);
        }
    }

    private List<ContextSegment> normalizeAndDeduplicate(
            List<ContextSegment> input, List<CompressionRecord> records) {
        Map<String, ContextSegment> deduplicated = new LinkedHashMap<>();
        for (ContextSegment original : input) {
            if (original == null || original.content == null || original.content.isBlank()) {
                continue;
            }
            ContextSegment normalized = original.copy();
            int before = tokensForSegment(normalized);
            normalized.content = normalizeText(normalized.content);
            normalized.tokenCount = tokensForSegment(normalized);
            String key = firstNonBlank(normalized.sourceRef, sha256(normalized.type + ":" + normalized.content));
            ContextSegment existing = deduplicated.get(key);
            if (existing == null || normalized.priority > existing.priority) {
                deduplicated.put(key, normalized);
            }
            if (normalized.tokenCount < before) {
                records.add(record("L0_NORMALIZE", normalized.type, before,
                        normalized.tokenCount, 0, "Whitespace and schema normalization"));
            }
        }
        if (deduplicated.size() < input.size()) {
            CompressionRecord record = record("L0_DEDUPLICATE", null,
                    totalTokens(input), totalTokens(new ArrayList<>(deduplicated.values())),
                    input.size() - deduplicated.size(), "Duplicate sourceRef/contentHash");
            records.add(record);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private void deterministicCompression(List<ContextSegment> segments, ContextEnvelope envelope) {
        List<ContextSegment> ordered = segments.stream()
                .filter(segment -> segment.compressible)
                .sorted(Comparator.comparingInt(segment -> segment.priority))
                .toList();
        for (ContextSegment segment : ordered) {
            if (totalTokens(segments) <= envelope.budget.softLimitTokens) {
                break;
            }
            int before = segment.tokenCount;
            int target = deterministicTarget(segment.type, before);
            if (target >= before) {
                continue;
            }
            segment.content = truncateToTokens(segment.content,
                    Math.max(1, target - segmentOverhead(segment.type)));
            segment.tokenCount = tokensForSegment(segment);
            envelope.compressionRecords.add(record(
                    "L1_DETERMINISTIC", segment.type, before, segment.tokenCount, 0,
                    "Soft token budget exceeded"));
        }
    }

    private void semanticCompression(List<ContextSegment> segments, ContextEnvelope envelope) {
        Set<ContextSegmentType> eligible = Set.of(
                ContextSegmentType.RECENT_CONVERSATION,
                ContextSegmentType.CONVERSATION_SUMMARY,
                ContextSegmentType.WORKING_MEMORY,
                ContextSegmentType.TOOL_OBSERVATION,
                ContextSegmentType.PLAN_STATE);
        for (ContextSegment segment : segments.stream()
                .filter(item -> item.compressible && eligible.contains(item.type))
                .sorted(Comparator.comparingInt(item -> item.priority))
                .toList()) {
            if (totalTokens(segments) <= envelope.budget.maxPromptTokens) {
                break;
            }
            int before = segment.tokenCount;
            ContextSemanticCompressionService.CompressionResult result = semanticCompressionService.compress(
                    segment.type, segment.content, Math.max(80, before / 2));
            if (!result.applied()) {
                continue;
            }
            segment.content = result.text();
            segment.tokenCount = tokensForSegment(segment);
            CompressionRecord record = record("L3_MODEL_SUMMARY", segment.type,
                    before, segment.tokenCount, 0, "Hard token budget predicted");
            record.modelUsed = result.modelUsed();
            record.cacheHit = result.cacheHit();
            envelope.compressionRecords.add(record);
            envelope.stats.modelCompressionUsed = true;
        }
    }

    private void dropLowPrioritySegments(List<ContextSegment> segments, ContextEnvelope envelope) {
        Set<ContextSegmentType> required = Set.of(
                ContextSegmentType.SYSTEM_POLICY,
                ContextSegmentType.USER_QUERY,
                ContextSegmentType.RETRIEVAL_EVIDENCE,
                ContextSegmentType.EXTERNAL_EVIDENCE);
        List<ContextSegment> removable = segments.stream()
                .filter(item -> item.compressible && !required.contains(item.type))
                .sorted(Comparator.comparingInt(item -> item.priority))
                .collect(Collectors.toCollection(ArrayList::new));
        for (ContextSegment segment : removable) {
            if (totalTokens(segments) <= envelope.budget.maxPromptTokens) {
                break;
            }
            segments.remove(segment);
            envelope.stats.droppedSegments.add(segment.segmentId);
            envelope.compressionRecords.add(record("L1_DROP_LOW_PRIORITY", segment.type,
                    segment.tokenCount, 0, 1, "Hard token budget exceeded"));
        }
    }

    private ContextBudgetPolicy budget(String strategy, Integer requestedPrompt, Integer requestedOutput) {
        ContextBudgetPolicy result = new ContextBudgetPolicy();
        result.strategy = strategy;
        ModelCapabilityRegistry.ModelCapability capability = modelCapabilityRegistry == null
                ? new ModelCapabilityRegistry.ModelCapability(
                        "configured-chat-model", 32768, 8192, "compatible-cjk-estimator-v1")
                : modelCapabilityRegistry.primary();
        result.modelName = capability.modelName();
        result.tokenizer = capability.tokenizer();
        result.contextWindow = capability.contextWindow();
        int configured = switch (strategy) {
            case "DEEP_RESEARCH" -> deepResearchMaxTokens;
            case "EVIDENCE_WORKFLOW" -> workflowMaxTokens;
            default -> quickQaMaxTokens;
        };
        int outputTokens = requestedOutput == null
                ? ("DEEP_RESEARCH".equals(strategy) ? 4096 : 2048)
                : Math.max(256, Math.min(requestedOutput, capability.maxOutputTokens()));
        int modelPromptLimit = Math.max(256, capability.contextWindow() - outputTokens);
        result.maxPromptTokens = requestedPrompt == null
                ? configured
                : Math.max(256, Math.min(requestedPrompt, configured));
        result.maxPromptTokens = Math.min(result.maxPromptTokens, modelPromptLimit);
        result.maxOutputTokens = outputTokens;
        result.softLimitRatio = softLimitRatio;
        result.softLimitTokens = (int) Math.floor(result.maxPromptTokens * softLimitRatio);
        return result;
    }

    private String renderPrompt(List<ContextSegment> segments) {
        StringBuilder builder = new StringBuilder();
        for (ContextSegment segment : segments) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append('[').append(segment.type.name()).append(']').append('\n')
                    .append(segment.content);
        }
        return builder.toString();
    }

    private String renderEvidence(EvidenceReference ref) {
        StringBuilder value = new StringBuilder();
        value.append('[').append(firstNonBlank(ref.citationId, "E?")).append("] ")
                .append(firstNonBlank(ref.title, "Untitled source"));
        if (ref.section != null && !ref.section.isBlank()) {
            value.append(" | section=").append(ref.section);
        }
        if (ref.documentId != null && !ref.documentId.isBlank()) {
            value.append(" | documentId=").append(ref.documentId);
        }
        if (ref.chunkId != null && !ref.chunkId.isBlank()) {
            value.append(" | chunkId=").append(ref.chunkId);
        }
        if (ref.url != null && !ref.url.isBlank()) {
            value.append(" | url=").append(ref.url);
        }
        if (ref.quote != null && !ref.quote.isBlank()) {
            value.append("\n").append(ref.quote);
        }
        return value.toString();
    }

    private String renderProjectContext(Map<String, Object> context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (context.containsKey("manifestCount")) {
            safe.put("manifestCount", context.get("manifestCount"));
        }
        if (context.containsKey("manifests")) {
            safe.put("manifests", context.get("manifests"));
        }
        if (context.containsKey("knowledgeSpaceStatus")) {
            safe.put("knowledgeSpaceStatus", context.get("knowledgeSpaceStatus"));
        }
        try {
            return objectMapper.writeValueAsString(safe.isEmpty() ? context : safe);
        } catch (JsonProcessingException e) {
            return String.valueOf(safe.isEmpty() ? context : safe);
        }
    }

    private void add(List<ContextSegment> segments, ContextSegmentType type, int priority,
                     String content, String sourceRef, boolean compressible) {
        if (content != null && !content.isBlank()) {
            segments.add(segment(type, priority, content, sourceRef, compressible));
        }
    }

    private ContextSegment segment(ContextSegmentType type, int priority,
                                   String content, String sourceRef, boolean compressible) {
        ContextSegment segment = new ContextSegment();
        segment.segmentId = "seg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        segment.type = type;
        segment.priority = priority;
        segment.content = content;
        segment.sourceRef = sourceRef;
        segment.compressible = compressible;
        segment.tokenCount = tokensForSegment(segment);
        return segment;
    }

    private CompressionRecord record(String stage, ContextSegmentType type, int before,
                                     int after, int dropped, String reason) {
        CompressionRecord record = new CompressionRecord();
        record.stage = stage;
        record.segmentType = type;
        record.beforeTokens = before;
        record.afterTokens = after;
        record.droppedItems = dropped;
        record.reason = reason;
        return record;
    }

    private int deterministicTarget(ContextSegmentType type, int current) {
        int limit = switch (type) {
            case DIAGNOSTICS -> 160;
            case LONG_TERM_MEMORY -> 240;
            case KNOWLEDGE_SPACE_SUMMARY -> 320;
            case RECENT_CONVERSATION, CONVERSATION_SUMMARY -> 600;
            case TOOL_OBSERVATION -> 500;
            case PLAN_STATE, WORKING_MEMORY -> 1000;
            case RETRIEVAL_EVIDENCE, EXTERNAL_EVIDENCE -> 420;
            default -> current;
        };
        return Math.min(current, limit);
    }

    private String truncateToTokens(String content, int maxTokens) {
        if (tokenEstimator.estimate(content) <= maxTokens) {
            return content;
        }
        int low = 0;
        int high = content.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            String candidate = content.substring(0, safeBoundary(content, middle));
            if (tokenEstimator.estimate(candidate) <= Math.max(1, maxTokens - 4)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return content.substring(0, safeBoundary(content, low)).stripTrailing() + " ...";
    }

    private int safeBoundary(String value, int index) {
        int safe = Math.max(0, Math.min(index, value.length()));
        if (safe > 0 && safe < value.length()
                && Character.isHighSurrogate(value.charAt(safe - 1))
                && Character.isLowSurrogate(value.charAt(safe))) {
            return safe - 1;
        }
        return safe;
    }

    private String normalizeText(String value) {
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String fingerprint(List<ContextSegment> segments) {
        String canonical = segments.stream()
                .sorted(Comparator
                        .comparing((ContextSegment item) -> item.type.name())
                        .thenComparing(item -> firstNonBlank(item.sourceRef, ""))
                        .thenComparing(item -> item.content))
                .map(item -> item.type + "|" + item.sourceRef + "|" + item.content)
                .collect(Collectors.joining("\n"));
        return "sha256:" + sha256(canonical);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private List<EvidenceReference> copyEvidence(List<EvidenceReference> input) {
        return new ArrayList<>(safeList(input));
    }

    private void refreshTokens(List<ContextSegment> segments) {
        for (ContextSegment segment : segments) {
            segment.tokenCount = tokensForSegment(segment);
        }
    }

    private int tokensForSegment(ContextSegment segment) {
        if (segment == null) {
            return 0;
        }
        return tokenEstimator.estimate(segment.content) + segmentOverhead(segment.type);
    }

    private int segmentOverhead(ContextSegmentType type) {
        return tokenEstimator.estimate("[" + (type == null ? "UNKNOWN" : type.name()) + "]");
    }

    private int totalTokens(List<ContextSegment> segments) {
        return safeList(segments).stream().filter(java.util.Objects::nonNull)
                .mapToInt(segment -> Math.max(0, segment.tokenCount)).sum();
    }

    private String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "QUICK_QA";
        }
        String normalized = strategy.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "DEEP_RESEARCH", "REACT" -> "DEEP_RESEARCH";
            case "EVIDENCE_WORKFLOW", "WORKFLOW" -> "EVIDENCE_WORKFLOW";
            default -> "QUICK_QA";
        };
    }

    private String value(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
