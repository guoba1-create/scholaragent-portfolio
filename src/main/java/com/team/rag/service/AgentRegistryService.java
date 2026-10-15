package com.team.rag.service;

import com.team.rag.agent.routing.ExecutionCatalogService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Minimal agent registry exposed to frontend/ops panels.
 * The runtime implementation lives in Python, while this registry provides
 * a stable, typed backend API for UI and diagnostics.
 */
@Service
public class AgentRegistryService {

    private final ExecutionCatalogService catalogService;

    public AgentRegistryService(ExecutionCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public List<Map<String, Object>> listAgents() {
        return catalogService.agents();
    }
}
