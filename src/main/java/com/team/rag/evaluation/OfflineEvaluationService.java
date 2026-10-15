package com.team.rag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.routing.ExecutionCatalogService;
import com.team.rag.agent.routing.ExecutionDecision;
import com.team.rag.agent.routing.ExecutionDecisionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runs the versioned, reproducible routing baseline without inventing metrics. */
@Service
public class OfflineEvaluationService {

    public static final String DEFAULT_SUITE = "agent-eval-v1";
    public static final String DATASET_VERSION = "2026-07-16";
    private static final Set<String> ALLOWED_SUITES = Set.of(DEFAULT_SUITE);

    private final ExecutionDecisionService decisionService;
    private final ObjectMapper objectMapper;
    private final RetrievalMetricCalculator retrievalMetricCalculator;

    public OfflineEvaluationService(ExecutionDecisionService decisionService,
                                    ObjectMapper objectMapper,
                                    RetrievalMetricCalculator retrievalMetricCalculator) {
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
        this.retrievalMetricCalculator = retrievalMetricCalculator;
    }

    public EvaluationReport run(String requestedSuite, Integer requestedLimit) {
        String suite = normalizeSuite(requestedSuite);
        List<EvaluationSample> samples = loadSuite(suite);
        int limit = requestedLimit == null ? samples.size() : Math.max(1, Math.min(requestedLimit, samples.size()));
        return evaluate(suite, samples.subList(0, limit));
    }

    EvaluationReport evaluate(String suite, List<EvaluationSample> samples) {
        Instant started = Instant.now();
        EvaluationReport report = new EvaluationReport();
        report.evaluationId = "eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        report.suite = suite;
        report.datasetVersion = DATASET_VERSION;
        report.catalogVersion = ExecutionCatalogService.CATALOG_VERSION;
        report.status = "RUNNING";
        report.startedAt = started;
        report.totalSamples = samples.size();

        Map<String, Integer> support = new LinkedHashMap<>();
        Map<String, Integer> predicted = new LinkedHashMap<>();
        Map<String, Integer> truePositive = new LinkedHashMap<>();
        Set<String> labels = new LinkedHashSet<>();

        for (EvaluationSample sample : samples) {
            String expectedIntent = normalize(sample.expectedIntent);
            String expectedStrategy = normalize(sample.expectedStrategy);
            labels.add(expectedIntent);
            support.merge(expectedIntent, 1, Integer::sum);
            try {
                ExecutionDecision actual = decisionService.decide(sample.query, "auto", "auto");
                String actualIntent = actual.intent == null ? "UNKNOWN" : actual.intent.name();
                String actualStrategy = actual.strategy == null ? "UNKNOWN" : actual.strategy.name();
                labels.add(actualIntent);
                predicted.merge(actualIntent, 1, Integer::sum);
                report.confusionMatrix
                        .computeIfAbsent(expectedIntent, ignored -> new LinkedHashMap<>())
                        .merge(actualIntent, 1, Integer::sum);
                boolean intentCorrect = expectedIntent.equals(actualIntent);
                boolean strategyCorrect = expectedStrategy.equals(actualStrategy);
                if (intentCorrect) {
                    report.correctIntent++;
                    truePositive.merge(expectedIntent, 1, Integer::sum);
                }
                if (strategyCorrect) {
                    report.correctStrategy++;
                }
                if (!intentCorrect || !strategyCorrect) {
                    report.failures.add(failure(sample, actualIntent, actualStrategy, actual.reason));
                }
                report.evaluatedSamples++;
            } catch (Exception error) {
                report.failures.add(failure(sample, "ERROR", "ERROR", error.getMessage()));
            }
        }

        double f1Total = 0.0;
        for (String label : labels) {
            EvaluationReport.ClassMetrics metrics = new EvaluationReport.ClassMetrics();
            metrics.support = support.getOrDefault(label, 0);
            metrics.predicted = predicted.getOrDefault(label, 0);
            metrics.truePositive = truePositive.getOrDefault(label, 0);
            metrics.precision = ratio(metrics.truePositive, metrics.predicted);
            metrics.recall = ratio(metrics.truePositive, metrics.support);
            metrics.f1 = metrics.precision + metrics.recall == 0.0
                    ? 0.0 : round(2.0 * metrics.precision * metrics.recall / (metrics.precision + metrics.recall));
            f1Total += metrics.f1;
            report.byIntent.put(label, metrics);
        }
        report.intentAccuracy = ratio(report.correctIntent, samples.size());
        report.strategyAccuracy = ratio(report.correctStrategy, samples.size());
        report.intentMacroF1 = labels.isEmpty() ? 0.0 : round(f1Total / labels.size());
        List<RetrievalEvaluationCase> retrievalCases = samples.stream()
                .map(this::toRetrievalCase)
                .toList();
        report.retrievalMetrics = retrievalMetricCalculator.calculate(retrievalCases, 10);
        if ("NOT_EVALUATED".equals(report.retrievalMetrics.status)) {
            report.unavailableMetrics.add(
                    "Retrieval Recall@K/MRR/NDCG require goldChunkIds and ranked retrievedChunkIds.");
        }
        report.unavailableMetrics.add("Citation precision and groundedness require generated answers plus human-verified evidence labels.");
        report.completedAt = Instant.now();
        report.durationMs = Duration.between(started, report.completedAt).toMillis();
        report.status = report.evaluatedSamples == samples.size() ? "COMPLETED" : "PARTIAL";
        return report;
    }

    private List<EvaluationSample> loadSuite(String suite) {
        ClassPathResource resource = new ClassPathResource("evaluation/" + suite + ".jsonl");
        List<EvaluationSample> samples = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.stripLeading().startsWith("#")) {
                    samples.add(objectMapper.readValue(line, EvaluationSample.class));
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load evaluation suite " + suite, error);
        }
        if (samples.size() < 50) {
            throw new IllegalStateException("Evaluation suite must contain at least 50 samples: " + suite);
        }
        return samples;
    }

    private String normalizeSuite(String value) {
        String suite = value == null || value.isBlank() ? DEFAULT_SUITE : value.trim();
        if (!ALLOWED_SUITES.contains(suite)) {
            throw new IllegalArgumentException("Unknown evaluation suite: " + suite);
        }
        return suite;
    }

    private RetrievalEvaluationCase toRetrievalCase(EvaluationSample sample) {
        RetrievalEvaluationCase value = new RetrievalEvaluationCase();
        value.sampleId = sample.id;
        value.goldChunkIds = sample.goldChunkIds;
        value.retrievedChunkIds = sample.retrievedChunkIds;
        return value;
    }

    private EvaluationReport.Failure failure(EvaluationSample sample, String actualIntent,
                                              String actualStrategy, String reason) {
        EvaluationReport.Failure value = new EvaluationReport.Failure();
        value.sampleId = sample.id;
        value.query = sample.query;
        value.expectedIntent = normalize(sample.expectedIntent);
        value.actualIntent = actualIntent;
        value.expectedStrategy = normalize(sample.expectedStrategy);
        value.actualStrategy = actualStrategy;
        value.reason = reason;
        return value;
    }

    private String normalize(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : round((double) numerator / denominator);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
