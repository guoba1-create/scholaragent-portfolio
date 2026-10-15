package com.team.rag.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs full-stack readiness after Spring startup and optionally fails fast. */
@Component
public class StartupReadinessReporter {

    private static final Logger log = LoggerFactory.getLogger(StartupReadinessReporter.class);

    private final RuntimeReadinessService readinessService;
    private final boolean failFast;

    public StartupReadinessReporter(RuntimeReadinessService readinessService,
                                    @Value("${app.readiness.fail-fast:false}") boolean failFast) {
        this.readinessService = readinessService;
        this.failFast = failFast;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        RuntimeReadinessService.ReadinessReport report = readinessService.check();
        report.components().forEach(component -> log.info(
                "Runtime readiness | component={} | status={} | required={} | detail={}",
                component.name(), component.status(), component.required(), component.detail()));
        if (!report.ready()) {
            log.warn("Agent stack started but is NOT_READY. Inspect /api/health/readiness.");
            if (failFast) {
                throw new IllegalStateException("Agent stack readiness check failed.");
            }
        }
    }
}
