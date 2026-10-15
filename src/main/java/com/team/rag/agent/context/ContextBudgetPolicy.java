package com.team.rag.agent.context;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContextBudgetPolicy {
    public String strategy;
    public String modelName;
    public String tokenizer;
    public int contextWindow;
    public int maxPromptTokens;
    public int maxOutputTokens;
    public double softLimitRatio = 0.75;
    public int softLimitTokens;
    public int usedPromptTokens;
    public int remainingPromptTokens;
    public boolean softLimitExceeded;
    public boolean hardLimitExceeded;
}
