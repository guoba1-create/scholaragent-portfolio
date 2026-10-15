package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.context.ContextEnvelope;
import com.team.rag.agent.routing.ExecutionDecision;
import com.team.rag.agent.routing.ExecutionIntent;
import com.team.rag.agent.routing.ExecutionStrategy;
import com.team.rag.identity.RequestIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSnapshotViewServiceTest {

    private final WorkflowSnapshotViewService service =
            new WorkflowSnapshotViewService(new ObjectMapper().findAndRegisterModules());

    @Test
    void ordinaryUserKeepsAnswerAndEvidenceButLosesWorkbenchInternals() {
        WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot();
        snapshot.workflowId = "wf-1";
        snapshot.status = "COMPLETED";
        snapshot.finalAnswer = "answer [E1]";
        snapshot.metadata = Map.of("userId", "u1", "researchGroupId", "s1");
        snapshot.contextEnvelope = new ContextEnvelope();
        snapshot.traces = List.of(new com.team.rag.agent.trace.AgentTraceStep());
        snapshot.executionDecision = new ExecutionDecision();
        snapshot.executionDecision.intent = ExecutionIntent.PAPER_QA;
        snapshot.executionDecision.strategy = ExecutionStrategy.QUICK_QA;
        snapshot.executionDecision.reason = "internal routing reason";
        ToolObservation observation = new ToolObservation();
        observation.rawPayloadPreview = "secret tool payload";
        snapshot.toolObservations = List.of(observation);

        WorkflowRunSnapshot view = service.forIdentity(
                snapshot, new RequestIdentity("u1", "s1", "web", false));

        assertThat(view.finalAnswer).isEqualTo("answer [E1]");
        assertThat(view.executionDecision.strategy).isEqualTo(ExecutionStrategy.QUICK_QA);
        assertThat(view.executionDecision.reason).isNull();
        assertThat(view.contextEnvelope).isNull();
        assertThat(view.toolObservations).isEmpty();
        assertThat(view.traces).isEmpty();
        assertThat(snapshot.contextEnvelope).isNotNull();
        assertThat(snapshot.toolObservations).hasSize(1);
    }

    @Test
    void administratorReceivesAuthoritativeSnapshot() {
        WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot();
        assertThat(service.forIdentity(snapshot,
                new RequestIdentity("u1", "s1", "web", true))).isSameAs(snapshot);
    }
}
