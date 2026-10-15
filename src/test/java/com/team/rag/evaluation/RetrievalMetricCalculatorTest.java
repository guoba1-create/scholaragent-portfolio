package com.team.rag.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalMetricCalculatorTest {

    @Test
    void calculatesBinaryRelevanceMetricsAndDeduplicatesRankedIds() {
        RetrievalEvaluationCase first = sample(
                "s1", List.of("c2", "c4"), List.of("c1", "c2", "c2", "c4"));
        RetrievalEvaluationCase second = sample(
                "s2", List.of("c9"), List.of("c8", "c7"));

        RetrievalMetricsReport report = new RetrievalMetricCalculator()
                .calculate(List.of(first, second), 3);

        assertThat(report.status).isEqualTo("COMPLETED");
        assertThat(report.evaluatedCases).isEqualTo(2);
        assertThat(report.recallAtK).isEqualTo(0.5);
        assertThat(report.meanReciprocalRank).isEqualTo(0.25);
        assertThat(report.ndcgAtK).isBetween(0.34, 0.35);
        assertThat(report.emptyRetrievalRate).isZero();
        assertThat(report.cases.get(0).firstRelevantRank).isEqualTo(2);
        assertThat(report.cases.get(0).retrievedCount).isEqualTo(3);
    }

    @Test
    void skipsCasesWithoutGoldAndReportsUnavailableWhenAllAreMissing() {
        RetrievalEvaluationCase missing = sample("s1", List.of(), List.of("c1"));
        RetrievalMetricsReport report = new RetrievalMetricCalculator()
                .calculate(List.of(missing), 10);

        assertThat(report.status).isEqualTo("NOT_EVALUATED");
        assertThat(report.evaluatedCases).isZero();
        assertThat(report.skippedWithoutGold).isEqualTo(1);
        assertThat(report.warnings).isNotEmpty();
    }

    private RetrievalEvaluationCase sample(String id, List<String> gold, List<String> ranked) {
        RetrievalEvaluationCase value = new RetrievalEvaluationCase();
        value.sampleId = id;
        value.goldChunkIds = gold;
        value.retrievedChunkIds = ranked;
        return value;
    }
}
