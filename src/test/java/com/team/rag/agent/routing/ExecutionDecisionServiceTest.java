package com.team.rag.agent.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionDecisionServiceTest {

    private HybridRouterService hybridRouter;
    private ExecutionDecisionService service;

    @BeforeEach
    void setUp() {
        hybridRouter = mock(HybridRouterService.class);
        service = new ExecutionDecisionService(hybridRouter, new ExecutionCatalogService());
    }

    @Test
    void emptyInputRequiresClarificationWithoutRetrievalIntent() {
        when(hybridRouter.decide(anyString(), anyString(), anyString()))
                .thenReturn(HybridRouteDecision.of("rag", "paper_qa", "auto", 0.2, "fallback"));

        ExecutionDecision decision = service.decide("", "auto", "auto");

        assertThat(decision.intent).isEqualTo(ExecutionIntent.CLARIFY);
        assertThat(decision.strategy).isEqualTo(ExecutionStrategy.QUICK_QA);
        assertThat(decision.requiresClarification).isTrue();
    }

    @Test
    void manualQuickModeCannotBeForcedBackToDeepResearch() {
        when(hybridRouter.decide(anyString(), anyString(), anyString()))
                .thenReturn(HybridRouteDecision.of(
                        "workflow", "deep_research", "deep_research_react", 0.9, "deep signal"));

        ExecutionDecision decision = service.decide("分析研究空白", "quick", "auto");

        assertThat(decision.strategy).isEqualTo(ExecutionStrategy.QUICK_QA);
        assertThat(decision.intent).isEqualTo(ExecutionIntent.PAPER_QA);
        assertThat(decision.engine).isEqualTo("rag");
        assertThat(decision.source).isEqualTo(DecisionSource.MANUAL);
    }

    @Test
    void evidenceModeSelectsDeterministicWorkflowTemplate() {
        when(hybridRouter.decide(anyString(), anyString(), anyString()))
                .thenReturn(HybridRouteDecision.of("rag", "paper_qa", "auto", 0.8, "qa"));

        ExecutionDecision decision = service.decide("这句话有依据吗？", "evidence", "auto");

        assertThat(decision.strategy).isEqualTo(ExecutionStrategy.EVIDENCE_WORKFLOW);
        assertThat(decision.workflowTemplate).isEqualTo(WorkflowTemplate.CLAIM_VERIFY);
        assertThat(decision.engine).isEqualTo("workflow");
    }

    @Test
    void rejectsDeprecatedOrUnknownProfile() {
        when(hybridRouter.decide(anyString(), anyString(), anyString()))
                .thenReturn(HybridRouteDecision.of("rag", "paper_qa", "auto", 0.8, "qa"));

        assertThatThrownBy(() -> service.decide("question", "auto", "paper_qa_react"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deprecated");
    }
}
