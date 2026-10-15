package com.team.rag.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdentityFilterTest {

    @Test
    void devModeOverridesCallerSuppliedTenantParameters() throws Exception {
        RequestIdentityFilter filter = filter("dev");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations");
        request.setParameter("userId", "spoofed-user");
        request.setParameter("researchGroupId", "spoofed-space");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> forwarded = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> forwarded.set((HttpServletRequest) req));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwarded.get().getParameter("userId")).isEqualTo("local-user");
        assertThat(forwarded.get().getParameter("researchGroupId")).isEqualTo("local-space");
        assertThat(forwarded.get().getAttribute(RequestIdentityFilter.USER_ATTRIBUTE)).isEqualTo("local-user");
        assertThat(forwarded.get().getAttribute(RequestIdentityFilter.ADMIN_ATTRIBUTE)).isEqualTo(false);
    }

    @Test
    void gatewayModeRejectsUntrustedIdentityHeaders() throws Exception {
        RequestIdentityFilter filter = filter("gateway");
        ReflectionTestUtils.setField(filter, "gatewayToken", "trusted-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflows/wf-a");
        request.addHeader("X-Authenticated-User-Id", "user-a");
        request.addHeader("X-Knowledge-Space-Id", "space-a");
        request.addHeader("X-Identity-Gateway-Token", "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> forwarded.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(forwarded).isFalse();
    }

    @Test
    void platformCallbacksAreNotRewritten() throws Exception {
        RequestIdentityFilter filter = filter("gateway");
        ReflectionTestUtils.setField(filter, "gatewayToken", "trusted-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/telegram/webhook");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> forwarded.set(true));

        assertThat(forwarded).isTrue();
    }

    @Test
    void gatewayModeGrantsAdminOnlyForMatchingAdminToken() throws Exception {
        RequestIdentityFilter filter = filter("gateway");
        ReflectionTestUtils.setField(filter, "gatewayToken", "trusted-secret");
        ReflectionTestUtils.setField(filter, "adminToken", "admin-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/evaluations/run");
        request.addHeader("X-Authenticated-User-Id", "user-a");
        request.addHeader("X-Knowledge-Space-Id", "space-a");
        request.addHeader("X-Identity-Gateway-Token", "trusted-secret");
        request.addHeader("X-Admin-Token", "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletRequest> forwarded = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> forwarded.set((HttpServletRequest) req));

        assertThat(forwarded.get().getAttribute(RequestIdentityFilter.ADMIN_ATTRIBUTE)).isEqualTo(true);
    }

    private RequestIdentityFilter filter(String mode) {
        RequestIdentityFilter filter = new RequestIdentityFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "mode", mode);
        ReflectionTestUtils.setField(filter, "userHeader", "X-Authenticated-User-Id");
        ReflectionTestUtils.setField(filter, "spaceHeader", "X-Knowledge-Space-Id");
        ReflectionTestUtils.setField(filter, "gatewayTokenHeader", "X-Identity-Gateway-Token");
        ReflectionTestUtils.setField(filter, "gatewayToken", "");
        ReflectionTestUtils.setField(filter, "devUserId", "local-user");
        ReflectionTestUtils.setField(filter, "devSpaceId", "local-space");
        ReflectionTestUtils.setField(filter, "adminTokenHeader", "X-Admin-Token");
        ReflectionTestUtils.setField(filter, "adminToken", "");
        ReflectionTestUtils.setField(filter, "devAutoAuthorizeAdmin", false);
        return filter;
    }
}
