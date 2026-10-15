package com.team.rag.evaluation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Reproducible binary-relevance retrieval metrics for one ranked variant. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetrievalMetricsReport {
    public String status;
    public int k;
    public int totalCases;
    public int evaluatedCases;
    public int skippedWithoutGold;
    public double recallAtK;
    public double meanReciprocalRank;
    public double ndcgAtK;
    public double emptyRetrievalRate;
    public List<CaseMetrics> cases = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CaseMetrics {
        public String sampleId;
        public int goldCount;
        public int retrievedCount;
        public int relevantRetrievedAtK;
        public Integer firstRelevantRank;
        public double recallAtK;
        public double reciprocalRank;
        public double ndcgAtK;
    }
}
