package com.team.rag.evaluation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual metrics from one offline evaluation run. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvaluationReport {
    public String evaluationId;
    public String suite;
    public String datasetVersion;
    public String catalogVersion;
    public String status;
    public int totalSamples;
    public int evaluatedSamples;
    public int correctIntent;
    public int correctStrategy;
    public double intentAccuracy;
    public double strategyAccuracy;
    public double intentMacroF1;
    public Map<String, Map<String, Integer>> confusionMatrix = new LinkedHashMap<>();
    public Map<String, ClassMetrics> byIntent = new LinkedHashMap<>();
    public List<Failure> failures = new ArrayList<>();
    public RetrievalMetricsReport retrievalMetrics;
    public List<String> unavailableMetrics = new ArrayList<>();
    public long durationMs;
    public Instant startedAt;
    public Instant completedAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClassMetrics {
        public int support;
        public int predicted;
        public int truePositive;
        public double precision;
        public double recall;
        public double f1;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Failure {
        public String sampleId;
        public String query;
        public String expectedIntent;
        public String actualIntent;
        public String expectedStrategy;
        public String actualStrategy;
        public String reason;
    }
}
