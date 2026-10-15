package com.team.rag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.routing.ExecutionDecision;
import com.team.rag.agent.routing.ExecutionDecisionService;
import com.team.rag.agent.routing.ExecutionIntent;
import com.team.rag.agent.routing.ExecutionStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfflineEvaluationServiceTest {

    @Test
    void loadsVersionedFiftySampleSuiteAndReportsActualFailures() {
        ExecutionDecisionService decisionService = mock(ExecutionDecisionService.class);
        ExecutionDecision decision = new ExecutionDecision();
        decision.intent = ExecutionIntent.PAPER_QA;
        decision.strategy = ExecutionStrategy.QUICK_QA;
        decision.reason = "test decision";
        when(decisionService.decide(anyString(), anyString(), anyString())).thenReturn(decision);

        OfflineEvaluationService service = new OfflineEvaluationService(
                decisionService, new ObjectMapper(), new RetrievalMetricCalculator());
        EvaluationReport report = service.run(OfflineEvaluationService.DEFAULT_SUITE, null);

        assertThat(report.status).isEqualTo("COMPLETED");
        assertThat(report.totalSamples).isEqualTo(50);
        assertThat(report.evaluatedSamples).isEqualTo(50);
        assertThat(report.intentAccuracy).isBetween(0.0, 1.0);
        assertThat(report.intentMacroF1).isBetween(0.0, 1.0);
        assertThat(report.failures).isNotEmpty();
        assertThat(report.retrievalMetrics.status).isEqualTo("NOT_EVALUATED");
        assertThat(report.unavailableMetrics).isNotEmpty();
    }
}
