package com.team.rag.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Establishes a trusted request identity for user-facing APIs.
 *
 * <p>In {@code dev} mode the two identity headers are optional and stable local
 * defaults are used. In {@code gateway} mode a trusted reverse proxy must add
 * the identity headers and a shared gateway token. Query parameters named
 * {@code userId} or {@code researchGroupId} are overwritten so old controllers
 * cannot accidentally trust caller-supplied tenant identifiers.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestIdentityFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = RequestIdentityFilter.class.getName() + ".userId";
    public static final String SPACE_ATTRIBUTE = RequestIdentityFilter.class.getName() + ".knowledgeSpaceId";
    public static final String SOURCE_ATTRIBUTE = RequestIdentityFilter.class.getName() + ".source";
    public static final String ADMIN_ATTRIBUTE = RequestIdentityFilter.class.getName() + ".admin";

    @Value("${app.identity.mode:dev}")
    private String mode;

    @Value("${app.identity.user-header:X-Authenticated-User-Id}")
    private String userHeader;

    @Value("${app.identity.space-header:X-Knowledge-Space-Id}")
    private String spaceHeader;

    @Value("${app.identity.gateway-token-header:X-Identity-Gateway-Token}")
    private String gatewayTokenHeader;

    @Value("${app.identity.gateway-token:}")
    private String gatewayToken;

    @Value("${app.identity.dev-user-id:local-user}")
    private String devUserId;

    @Value("${app.identity.dev-space-id:local-space}")
    private String devSpaceId;

    @Value("${app.admin.token-header:X-Admin-Token}")
    private String adminTokenHeader;

    @Value("${app.admin.token:}")
    private String adminToken;

    @Value("${app.admin.dev-auto-authorize:true}")
    private boolean devAutoAuthorizeAdmin;

    private final ObjectMapper objectMapper;

    public RequestIdentityFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        return path.startsWith("/api/wechat/")
                || path.startsWith("/api/telegram/")
                || path.startsWith("/api/catalog/")
                || path.startsWith("/api/router/")
                || path.startsWith("/api/health/")
                || path.startsWith("/api/architecture/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean gatewayMode = "gateway".equalsIgnoreCase(mode);
        if (gatewayMode && gatewayToken.isBlank()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Identity gateway mode is enabled but APP_IDENTITY_GATEWAY_TOKEN is not configured.");
            return;
        }
        if (gatewayMode && !gatewayToken.equals(request.getHeader(gatewayTokenHeader))) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Untrusted identity gateway.");
            return;
        }

        String userId = normalize(request.getHeader(userHeader));
        String spaceId = normalize(request.getHeader(spaceHeader));
        if (gatewayMode && (userId == null || spaceId == null)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Authenticated user and knowledge-space headers are required.");
            return;
        }
        if (userId == null) {
            userId = devUserId;
        }
        if (spaceId == null) {
            spaceId = devSpaceId;
        }

        request.setAttribute(USER_ATTRIBUTE, userId);
        request.setAttribute(SPACE_ATTRIBUTE, spaceId);
        request.setAttribute(SOURCE_ATTRIBUTE, "web");
        boolean admin = (!gatewayMode && devAutoAuthorizeAdmin)
                || secureEquals(adminToken, request.getHeader(firstNonBlank(adminTokenHeader, "X-Admin-Token")));
        request.setAttribute(ADMIN_ATTRIBUTE, admin);
        filterChain.doFilter(new IdentityParameterRequestWrapper(request, userId, spaceId), response);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean secureEquals(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", status == 401 ? "Unauthorized" : "IdentityConfigurationError",
                "message", message)));
    }

    private static final class IdentityParameterRequestWrapper extends HttpServletRequestWrapper {
        private final String userId;
        private final String spaceId;

        private IdentityParameterRequestWrapper(HttpServletRequest request, String userId, String spaceId) {
            super(request);
            this.userId = userId;
            this.spaceId = spaceId;
        }

        @Override
        public String getParameter(String name) {
            if ("userId".equals(name)) {
                return userId;
            }
            if ("researchGroupId".equals(name) || "knowledgeSpaceId".equals(name)) {
                return spaceId;
            }
            return super.getParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            String overridden = getParameter(name);
            if ("userId".equals(name) || "researchGroupId".equals(name) || "knowledgeSpaceId".equals(name)) {
                return new String[]{overridden};
            }
            return super.getParameterValues(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> values = new LinkedHashMap<>(super.getParameterMap());
            values.put("userId", new String[]{userId});
            values.put("researchGroupId", new String[]{spaceId});
            values.put("knowledgeSpaceId", new String[]{spaceId});
            return values;
        }
    }
}
