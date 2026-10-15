package com.team.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Auth filter for internal agent tool APIs.
 * Applies to /internal/** and /mcp/** runtime paths.
 * Blank tokens are blocked by default unless an explicit dev-only bypass flag is enabled.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalApiAuthFilter extends OncePerRequestFilter {

    @Value("${agent.internal.token:}")
    private String expectedToken;

    @Value("${agent.internal.token-header:X-Agent-Runtime-Token}")
    private String tokenHeader;

    @Value("${agent.internal.allow-blank-token:false}")
    private boolean allowBlankToken;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/internal/") && !path.startsWith("/mcp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (expectedToken.isBlank()) {
            if (allowBlankToken) {
                filterChain.doFilter(request, response);
                return;
            }
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Internal API token is not configured. Set agent.internal.token or explicitly enable agent.internal.allow-blank-token for local dev.");
            return;
        }

        String token = request.getHeader(tokenHeader);
        if (expectedToken.equals(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                "Missing or invalid " + tokenHeader + " header");
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("error", status == HttpServletResponse.SC_UNAUTHORIZED ? "Unauthorized" : "InternalApiMisconfigured",
                        "message", message)));
    }
}
