package com.team.rag.identity;

import com.team.rag.agent.runtime.WorkflowRunRequest;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Resolves filter-established identity and enforces workflow ownership. */
@Service
public class RequestIdentityResolver {

    public RequestIdentity resolve(HttpServletRequest request) {
        String userId = attribute(request, RequestIdentityFilter.USER_ATTRIBUTE);
        String spaceId = attribute(request, RequestIdentityFilter.SPACE_ATTRIBUTE);
        String source = attribute(request, RequestIdentityFilter.SOURCE_ATTRIBUTE);
        boolean admin = Boolean.TRUE.equals(request.getAttribute(RequestIdentityFilter.ADMIN_ATTRIBUTE));
        if (userId == null || spaceId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No trusted request identity is available.");
        }
        return new RequestIdentity(userId, spaceId, source == null ? "web" : source, admin);
    }

    public WorkflowRunRequest bind(WorkflowRunRequest request, HttpServletRequest servletRequest) {
        RequestIdentity identity = resolve(servletRequest);
        WorkflowRunRequest target = request == null ? new WorkflowRunRequest() : request;
        target.userId = identity.userId();
        target.researchGroupId = identity.knowledgeSpaceId();
        target.source = identity.source();
        return target;
    }

    public void assertWorkflowAccess(WorkflowRunSnapshot snapshot, HttpServletRequest request) {
        RequestIdentity identity = resolve(request);
        if (snapshot == null || "UNKNOWN".equalsIgnoreCase(snapshot.status)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found.");
        }
        Map<String, Object> metadata = snapshot.metadata;
        String owner = metadata == null ? null : value(metadata.get("userId"));
        String space = metadata == null ? null : value(metadata.get("researchGroupId"));
        if (!identity.userId().equals(owner) || !identity.knowledgeSpaceId().equals(space)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workflow belongs to another identity or knowledge space.");
        }
    }

    public RequestIdentity requireAdmin(HttpServletRequest request) {
        RequestIdentity identity = resolve(request);
        if (!identity.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator capability is required.");
        }
        return identity;
    }

    private String attribute(HttpServletRequest request, String key) {
        return value(request.getAttribute(key));
    }

    private String value(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
