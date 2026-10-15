package com.team.rag.agent.runtime;

import com.team.rag.agent.routing.HybridRouteDecision;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Selects the Java quick path or the Python LangGraph runtime. */
@Service
public class ExecutionRuntimeSelector {

    private final Environment environment;

    public ExecutionRuntimeSelector(Environment environment) {
        this.environment = environment;
    }

    /** Quick QA and fixed responses stay in Java; evidence/deep strategies run in Python. */
    public RuntimeTarget select(WorkflowRunRequest request, HybridRouteDecision decision) {
        String engine = decision != null ? decision.engine : request.engine;
        String taskType = decision != null ? decision.taskType : null;
        if ("reject".equals(taskType) || "clarification_needed".equals(taskType) || "rag".equals(engine)) {
            return RuntimeTarget.JAVA;
        }
        if (!isPythonEnabled()) {
            throw new IllegalStateException(
                    "Python LangGraph runtime is required for evidence workflow and deep research, but it is disabled.");
        }
        return RuntimeTarget.PYTHON;
    }

    public boolean isPythonEnabled() {
        return environment.getProperty("agent.runtime.python.enabled", Boolean.class, false);
    }
}
