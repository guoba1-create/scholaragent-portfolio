package com.team.rag.evaluation;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Calculates Recall@K, MRR, NDCG@K and empty-retrieval rate without model calls. */
@Service
public class RetrievalMetricCalculator {

    public RetrievalMetricsReport calculate(List<RetrievalEvaluationCase> input, int requestedK) {
        int k = Math.max(1, Math.min(requestedK, 100));
        List<RetrievalEvaluationCase> cases = input == null ? List.of() : input;
        RetrievalMetricsReport report = new RetrievalMetricsReport();
        report.k = k;
        report.totalCases = cases.size();

        double recallTotal = 0.0;
        double reciprocalRankTotal = 0.0;
        double ndcgTotal = 0.0;
        int emptyRetrievals = 0;

        for (RetrievalEvaluationCase evaluationCase : cases) {
            Set<String> gold = normalizedSet(evaluationCase == null ? null : evaluationCase.goldChunkIds);
            if (gold.isEmpty()) {
                report.skippedWithoutGold++;
                continue;
            }
            List<String> ranked = normalizedRanked(
                    evaluationCase == null ? null : evaluationCase.retrievedChunkIds);
            RetrievalMetricsReport.CaseMetrics metrics = calculateCase(
                    evaluationCase == null ? null : evaluationCase.sampleId, gold, ranked, k);
            report.cases.add(metrics);
            report.evaluatedCases++;
            recallTotal += metrics.recallAtK;
            reciprocalRankTotal += metrics.reciprocalRank;
            ndcgTotal += metrics.ndcgAtK;
            if (ranked.isEmpty()) {
                emptyRetrievals++;
            }
        }

        if (report.evaluatedCases == 0) {
            report.status = "NOT_EVALUATED";
            report.warnings.add("No case contains goldChunkIds; retrieval metrics were not calculated.");
            return report;
        }
        report.recallAtK = round(recallTotal / report.evaluatedCases);
        report.meanReciprocalRank = round(reciprocalRankTotal / report.evaluatedCases);
        report.ndcgAtK = round(ndcgTotal / report.evaluatedCases);
        report.emptyRetrievalRate = round((double) emptyRetrievals / report.evaluatedCases);
        report.status = report.skippedWithoutGold == 0 ? "COMPLETED" : "PARTIAL";
        if (report.skippedWithoutGold > 0) {
            report.warnings.add(report.skippedWithoutGold
                    + " case(s) were skipped because goldChunkIds is empty.");
        }
        return report;
    }

    private RetrievalMetricsReport.CaseMetrics calculateCase(
            String sampleId, Set<String> gold, List<String> ranked, int k) {
        RetrievalMetricsReport.CaseMetrics metrics = new RetrievalMetricsReport.CaseMetrics();
        metrics.sampleId = sampleId;
        metrics.goldCount = gold.size();
        metrics.retrievedCount = ranked.size();

        int limit = Math.min(k, ranked.size());
        double dcg = 0.0;
        for (int index = 0; index < limit; index++) {
            if (gold.contains(ranked.get(index))) {
                metrics.relevantRetrievedAtK++;
                dcg += discount(index + 1);
                if (metrics.firstRelevantRank == null) {
                    metrics.firstRelevantRank = index + 1;
                }
            }
        }
        metrics.recallAtK = round((double) metrics.relevantRetrievedAtK / gold.size());
        metrics.reciprocalRank = metrics.firstRelevantRank == null
                ? 0.0 : round(1.0 / metrics.firstRelevantRank);
        int idealRelevant = Math.min(gold.size(), k);
        double idealDcg = 0.0;
        for (int rank = 1; rank <= idealRelevant; rank++) {
            idealDcg += discount(rank);
        }
        metrics.ndcgAtK = idealDcg == 0.0 ? 0.0 : round(dcg / idealDcg);
        return metrics;
    }

    private Set<String> normalizedSet(List<String> values) {
        return new LinkedHashSet<>(normalizedRanked(values));
    }

    private List<String> normalizedRanked(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                unique.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(unique);
    }

    private double discount(int rank) {
        return 1.0 / (Math.log(rank + 1.0) / Math.log(2.0));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
