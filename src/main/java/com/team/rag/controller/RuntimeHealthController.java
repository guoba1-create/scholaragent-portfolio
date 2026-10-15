package com.team.rag.controller;

import com.team.rag.health.RuntimeReadinessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Application-level readiness endpoint for the complete Java/Python Agent stack. */
@RestController
@RequestMapping("/api/health")
public class RuntimeHealthController {

    private final RuntimeReadinessService readinessService;

    public RuntimeHealthController(RuntimeReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        RuntimeReadinessService.ReadinessReport report = readinessService.check();
        return ResponseEntity.status(report.ready() ? 200 : 503).body(report.asMap());
    }
}
