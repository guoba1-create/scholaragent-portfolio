package com.team.rag.controller;

import com.team.rag.agent.runtime.RawToolPayload;
import com.team.rag.agent.runtime.RawToolPayloadStore;
import com.team.rag.agent.runtime.WorkflowRunService;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import com.team.rag.identity.RequestIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only audit lookup for complete tool outputs. */
@RestController
@RequestMapping("/api/workflows/{workflowId}/tool-payload")
public class ToolPayloadController {

    private final WorkflowRunService workflowRunService;
    private final RawToolPayloadStore payloadStore;
    private final RequestIdentityResolver identityResolver;

    public ToolPayloadController(WorkflowRunService workflowRunService,
                                 RawToolPayloadStore payloadStore,
                                 RequestIdentityResolver identityResolver) {
        this.workflowRunService = workflowRunService;
        this.payloadStore = payloadStore;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public ResponseEntity<RawToolPayload> get(@PathVariable String workflowId,
                                              @RequestParam("ref") String payloadRef,
                                              HttpServletRequest request) {
        identityResolver.requireAdmin(request);
        WorkflowRunSnapshot snapshot = workflowRunService.getSnapshot(workflowId);
        identityResolver.assertWorkflowAccess(snapshot, request);
        return payloadStore.find(payloadRef)
                .filter(payload -> workflowId.equals(payload.workflowId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
