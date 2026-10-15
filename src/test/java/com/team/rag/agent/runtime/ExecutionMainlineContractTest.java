package com.team.rag.agent.runtime;

import com.team.rag.agent.routing.HybridRouteDecision;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionMainlineContractTest {

    @Test
    void quickQaStaysInJavaAndBothComplexStrategiesUsePython() {
        ExecutionRuntimeSelector selector = new ExecutionRuntimeSelector(
                new MockEnvironment().withProperty("agent.runtime.python.enabled", "true"));
        WorkflowRunRequest request = new WorkflowRunRequest();

        assertThat(selector.select(request, route("rag", "paper_qa"))).isEqualTo(RuntimeTarget.JAVA);
        assertThat(selector.select(request, route("workflow", "claim_verification"))).isEqualTo(RuntimeTarget.PYTHON);
        assertThat(selector.select(request, route("react", "deep_research"))).isEqualTo(RuntimeTarget.PYTHON);
    }

    @Test
    void complexStrategyFailsClearlyWhenPythonRuntimeIsDisabled() {
        ExecutionRuntimeSelector selector = new ExecutionRuntimeSelector(new MockEnvironment());

        assertThatThrownBy(() -> selector.select(
                new WorkflowRunRequest(), route("workflow", "claim_verification")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Python LangGraph runtime is required");
    }

    private HybridRouteDecision route(String engine, String taskType) {
        return HybridRouteDecision.of(engine, taskType, "auto", 0.9, "contract test");
    }
}
