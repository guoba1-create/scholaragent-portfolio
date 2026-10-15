package com.team.rag.monitoring;

import com.team.rag.agent.context.ContextEnvelope;
import com.team.rag.agent.routing.ExecutionDecision;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/** Low-cardinality business metrics for routing, context, retrieval, cache and runtime execution. */
@Service
public class AgentBusinessMetrics {

    private final MeterRegistry registry;

    public AgentBusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void decision(ExecutionDecision decision) {
        if (decision == null) {
            return;
        }
        registry.counter("agent.routing.decisions",
                "intent", tag(decision.intent),
                "strategy", tag(decision.strategy),
                "source", tag(decision.source)).increment();
        DistributionSummary.builder("agent.routing.confidence")
                .tag("strategy", tag(decision.strategy))
                .register(registry)
                .record(decision.confidence);
    }

    public void contextCompiled(ContextEnvelope envelope) {
        if (envelope == null || envelope.stats == null) {
            return;
        }
        String strategy = tag(envelope.strategy);
        registry.counter("agent.context.compilations",
                "strategy", strategy,
                "compressed", String.valueOf(!envelope.compressionRecords.isEmpty())).increment();
        DistributionSummary.builder("agent.context.tokens.original")
                .tag("strategy", strategy).register(registry).record(envelope.stats.originalTokens);
        DistributionSummary.builder("agent.context.tokens.final")
                .tag("strategy", strategy).register(registry).record(envelope.stats.finalTokens);
        DistributionSummary.builder("agent.context.compression.ratio")
                .tag("strategy", strategy).register(registry).record(envelope.stats.compressionRatio);
        if (!envelope.stats.citationsPreserved) {
            registry.counter("agent.context.citation_integrity_failures", "strategy", strategy).increment();
        }
    }

    public void cacheLookup(String namespace, String tier, boolean hit) {
        registry.counter("agent.cache.lookups",
                "namespace", namespaceTag(namespace),
                "tier", tag(tier),
                "result", hit ? "hit" : "miss").increment();
    }

    public void cacheError(String operation) {
        registry.counter("agent.cache.errors", "operation", tag(operation)).increment();
    }

    public void retrieval(String decision, int hits, long latencyMs) {
        String crag = tag(decision == null ? "unknown" : decision);
        registry.counter("agent.retrieval.requests", "crag", crag,
                "empty", String.valueOf(hits <= 0)).increment();
        DistributionSummary.builder("agent.retrieval.hits")
                .tag("crag", crag).register(registry).record(Math.max(0, hits));
        registry.timer("agent.retrieval.duration", "crag", crag)
                .record(Duration.ofMillis(Math.max(0, latencyMs)));
    }

    public void workflowTerminal(String strategy, String status, long latencyMs) {
        registry.counter("agent.workflow.terminal",
                "strategy", tag(strategy), "status", tag(status)).increment();
        registry.timer("agent.workflow.duration.business",
                "strategy", tag(strategy), "status", tag(status))
                .record(Duration.ofMillis(Math.max(0, latencyMs)));
    }

    public void toolExecution(String toolName, String status, long latencyMs) {
        registry.counter("agent.tool.executions",
                "tool", tag(toolName), "status", tag(status)).increment();
        registry.timer("agent.tool.duration", "tool", tag(toolName), "status", tag(status))
                .record(Duration.ofMillis(Math.max(0, latencyMs)));
    }

    private String namespaceTag(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return "unknown";
        }
        String normalized = namespace.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(':');
        return sanitize(separator < 0 ? normalized : normalized.substring(0, separator));
    }

    private String tag(Object value) {
        return sanitize(value == null ? "unknown" : String.valueOf(value).toLowerCase(Locale.ROOT));
    }

    private String sanitize(String value) {
        String safe = value.replaceAll("[^a-z0-9_.-]", "_");
        return safe.length() > 48 ? safe.substring(0, 48) : safe;
    }
}
