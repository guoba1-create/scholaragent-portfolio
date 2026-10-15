package com.team.rag.controller;

import com.team.rag.evaluation.EvaluationReport;
import com.team.rag.evaluation.OfflineEvaluationService;
import com.team.rag.evaluation.RetrievalEvaluationCase;
import com.team.rag.evaluation.RetrievalMetricCalculator;
import com.team.rag.evaluation.RetrievalMetricsReport;
import com.team.rag.identity.RequestIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin-facing offline evaluation endpoint. It never returns fabricated metrics. */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final OfflineEvaluationService evaluationService;
    private final RetrievalMetricCalculator retrievalMetricCalculator;
    private final RequestIdentityResolver identityResolver;

    public EvaluationController(OfflineEvaluationService evaluationService,
                                RetrievalMetricCalculator retrievalMetricCalculator,
                                RequestIdentityResolver identityResolver) {
        this.evaluationService = evaluationService;
        this.retrievalMetricCalculator = retrievalMetricCalculator;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/run")
    public ResponseEntity<EvaluationReport> run(@RequestBody(required = false) EvaluationRunRequest request,
                                                HttpServletRequest servletRequest) {
        identityResolver.requireAdmin(servletRequest);
        EvaluationRunRequest safe = request == null ? new EvaluationRunRequest() : request;
        return ResponseEntity.ok(evaluationService.run(safe.suite, safe.maxSamples));
    }

    @PostMapping("/retrieval-metrics")
    public ResponseEntity<RetrievalMetricsReport> retrievalMetrics(
            @RequestBody RetrievalMetricsRequest request,
            HttpServletRequest servletRequest) {
        identityResolver.requireAdmin(servletRequest);
        RetrievalMetricsRequest safe = request == null ? new RetrievalMetricsRequest() : request;
        return ResponseEntity.ok(retrievalMetricCalculator.calculate(safe.cases, safe.k));
    }

    @PostMapping("/retrieval-ablation")
    public ResponseEntity<Map<String, RetrievalMetricsReport>> retrievalAblation(
            @RequestBody RetrievalAblationRequest request,
            HttpServletRequest servletRequest) {
        identityResolver.requireAdmin(servletRequest);
        Map<String, RetrievalMetricsReport> reports = new LinkedHashMap<>();
        if (request != null && request.variants != null) {
            request.variants.forEach((name, cases) ->
                    reports.put(name, retrievalMetricCalculator.calculate(cases, request.k)));
        }
        return ResponseEntity.ok(reports);
    }

    public static class EvaluationRunRequest {
        public String suite = OfflineEvaluationService.DEFAULT_SUITE;
        public Integer maxSamples;
    }

    public static class RetrievalMetricsRequest {
        public int k = 10;
        public List<RetrievalEvaluationCase> cases = List.of();
    }

    public static class RetrievalAblationRequest {
        public int k = 10;
        public Map<String, List<RetrievalEvaluationCase>> variants = Map.of();
    }
}
