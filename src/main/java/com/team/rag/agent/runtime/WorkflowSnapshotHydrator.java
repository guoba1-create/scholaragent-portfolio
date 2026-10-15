package com.team.rag.agent.runtime;

import com.team.rag.agent.trace.AgentTraceService;
import org.springframework.stereotype.Service;

/** Joins event/trace/step/message stores into the API-facing workflow snapshot. */
@Service
public class WorkflowSnapshotHydrator {

    private final AgentMessageBus messageBus;
    private final AgentTraceService traceService;
    private final WorkflowEventBus eventBus;
    private final ReActStepStore stepStore;
    private final ToolObservationStore observationStore;

    public WorkflowSnapshotHydrator(
            AgentMessageBus messageBus,
            AgentTraceService traceService,
            WorkflowEventBus eventBus,
            ReActStepStore stepStore,
            ToolObservationStore observationStore) {
        this.messageBus = messageBus;
        this.traceService = traceService;
        this.eventBus = eventBus;
        this.stepStore = stepStore;
        this.observationStore = observationStore;
    }

    public void hydrate(WorkflowRunSnapshot snapshot) {
        if (snapshot == null || snapshot.workflowId == null) {
            return;
        }
        snapshot.events = eventBus.getEvents(snapshot.workflowId);
        snapshot.traces = traceService.getWorkflowTraces(snapshot.workflowId);
        snapshot.stats = traceService.getWorkflowStatistics(snapshot.workflowId);
        snapshot.reactSteps = stepStore.getSteps(snapshot.workflowId);
        snapshot.toolObservations = observationStore.getObservations(snapshot.workflowId);
        snapshot.messages = messageBus.getWorkflowMessages(snapshot.workflowId);
    }

    public void clearTransientRunData(String workflowId) {
        observationStore.clear(workflowId);
    }
}
