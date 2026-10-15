package com.team.rag.identity;

import com.team.rag.agent.runtime.WorkflowRunRequest;
import com.team.rag.agent.runtime.WorkflowRunSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdentityResolverTest {

    private final RequestIdentityResolver resolver = new RequestIdentityResolver();

    @Test
    void bindOverwritesJsonIdentityFields() {
        MockHttpServletRequest servletRequest = identityRequest("server-user", "server-space");
        WorkflowRunRequest request = new WorkflowRunRequest();
        request.userId = "spoofed-user";
        request.researchGroupId = "spoofed-space";

        WorkflowRunRequest bound = resolver.bind(request, servletRequest);

        assertThat(bound.userId).isEqualTo("server-user");
        assertThat(bound.researchGroupId).isEqualTo("server-space");
    }

    @Test
    void workflowAccessRequiresBothOwnerAndKnowledgeSpace() {
        WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot();
        snapshot.status = "COMPLETED";
        snapshot.metadata = Map.of("userId", "user-a", "researchGroupId", "space-a");

        resolver.assertWorkflowAccess(snapshot, identityRequest("user-a", "space-a"));

        assertThatThrownBy(() -> resolver.assertWorkflowAccess(
                snapshot, identityRequest("user-b", "space-a")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void adminCapabilityIsExplicitAndCannotBeInferredFromOwner() {
        MockHttpServletRequest normal = identityRequest("user-a", "space-a");
        assertThatThrownBy(() -> resolver.requireAdmin(normal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        normal.setAttribute(RequestIdentityFilter.ADMIN_ATTRIBUTE, true);
        assertThat(resolver.requireAdmin(normal).admin()).isTrue();
    }

    private MockHttpServletRequest identityRequest(String userId, String spaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdentityFilter.USER_ATTRIBUTE, userId);
        request.setAttribute(RequestIdentityFilter.SPACE_ATTRIBUTE, spaceId);
        request.setAttribute(RequestIdentityFilter.SOURCE_ATTRIBUTE, "web");
        return request;
    }
}
