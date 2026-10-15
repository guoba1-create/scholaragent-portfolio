package com.team.rag.controller;

import com.team.rag.agent.context.ContextCompileRequest;
import com.team.rag.agent.context.ContextCompilerService;
import com.team.rag.agent.context.ContextEnvelope;
import com.team.rag.agent.context.EvidenceReference;
import com.team.rag.agent.runtime.MemoryRecallRecord;
import com.team.rag.agent.runtime.ToolObservation;
import com.team.rag.identity.RequestIdentity;
import com.team.rag.identity.RequestIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Context budget inspection API. It compiles data but never calls an answer model. */
@RestController
@RequestMapping("/api/context")
public class ContextController {

    private final ContextCompilerService compiler;
    private final RequestIdentityResolver identityResolver;

    public ContextController(ContextCompilerService compiler,
                             RequestIdentityResolver identityResolver) {
        this.compiler = compiler;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/preview")
    public ResponseEntity<ContextEnvelope> preview(@RequestBody(required = false) ContextPreviewRequest body,
                                                   HttpServletRequest servletRequest) {
        RequestIdentity identity = identityResolver.resolve(servletRequest);
        identityResolver.requireAdmin(servletRequest);
        ContextPreviewRequest safe = body == null ? new ContextPreviewRequest() : body;
        ContextCompileRequest request = new ContextCompileRequest();
        request.workflowId = "preview_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        request.strategy = safe.strategy;
        request.systemPolicy = "Preview for owner=" + identity.userId()
                + ", knowledgeSpace=" + identity.knowledgeSpaceId();
        request.userQuery = safe.message;
        request.recentConversation = copy(safe.recentConversation);
        request.conversationSummary = safe.conversationSummary;
        request.memoryRecall = safe.memoryRecall;
        request.evidenceRefs = copy(safe.evidenceRefs);
        request.planState = safe.planState;
        request.workingMemory = safe.workingMemory;
        request.toolObservations = copy(safe.toolObservations);
        request.diagnostics = copy(safe.diagnostics);
        request.maxPromptTokens = safe.maxPromptTokens;
        request.maxOutputTokens = safe.maxOutputTokens;
        request.allowModelCompression = safe.allowModelCompression;
        return ResponseEntity.ok(compiler.compile(request));
    }

    private <T> List<T> copy(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    public static class ContextPreviewRequest {
        public String strategy = "QUICK_QA";
        public String message = "";
        public List<String> recentConversation = new ArrayList<>();
        public String conversationSummary;
        public MemoryRecallRecord memoryRecall;
        public List<EvidenceReference> evidenceRefs = new ArrayList<>();
        public String planState;
        public String workingMemory;
        public List<ToolObservation> toolObservations = new ArrayList<>();
        public List<String> diagnostics = new ArrayList<>();
        public Integer maxPromptTokens;
        public Integer maxOutputTokens;
        public boolean allowModelCompression = false;
    }
}
