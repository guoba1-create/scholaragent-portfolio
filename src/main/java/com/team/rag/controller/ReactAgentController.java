package com.team.rag.controller;

import com.team.rag.service.AgentRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compatibility endpoints for Agent registry browsing.
 */
@RestController
@RequestMapping("/api/react-agent")
public class ReactAgentController {

    private final AgentRegistryService registryService;

    public ReactAgentController(AgentRegistryService registryService) {
        this.registryService = registryService;
    }

    @GetMapping("/agents")
    public ResponseEntity<Map<String, Object>> listAgents() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "java-registry");
        payload.put("count", registryService.listAgents().size());
        payload.put("agents", registryService.listAgents());
        return ResponseEntity.ok(payload);
    }
}
