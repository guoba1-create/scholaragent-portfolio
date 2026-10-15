package com.team.rag.agent.context;

public class ContextBudgetExceededException extends IllegalStateException {
    public ContextBudgetExceededException(String message) {
        super(message);
    }
}
