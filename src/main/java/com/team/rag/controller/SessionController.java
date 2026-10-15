package com.team.rag.controller;

import com.team.rag.identity.RequestIdentity;
import com.team.rag.identity.RequestIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Returns server-derived UI capabilities without exposing identity secrets. */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final RequestIdentityResolver identityResolver;

    public SessionController(RequestIdentityResolver identityResolver) {
        this.identityResolver = identityResolver;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(HttpServletRequest request) {
        RequestIdentity identity = identityResolver.resolve(request);
        return Map.of(
                "adminWorkbench", identity.admin(),
                "source", identity.source(),
                "features", identity.admin()
                        ? new String[]{"console", "documents", "memory", "workbench", "evaluation", "rawToolPayload"}
                        : new String[]{"console", "documents", "memory"});
    }
}
