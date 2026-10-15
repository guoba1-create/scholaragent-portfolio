package com.team.rag.controller;

import com.team.rag.agent.routing.ExecutionCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class ExecutionCatalogController {

    private final ExecutionCatalogService catalogService;

    public ExecutionCatalogController(ExecutionCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/execution")
    public ResponseEntity<Map<String, Object>> executionCatalog() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", ExecutionCatalogService.CATALOG_VERSION);
        body.put("modes", catalogService.publicModes());
        body.put("workflowTemplates", catalogService.workflowTemplates());
        body.put("agents", catalogService.agents());
        return ResponseEntity.ok(body);
    }
}
