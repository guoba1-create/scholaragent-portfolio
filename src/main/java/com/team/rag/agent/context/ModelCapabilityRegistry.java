package com.team.rag.agent.context;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Central model-window contract used by prompt budget allocation. */
@Service
public class ModelCapabilityRegistry {

    private final ModelCapability primary;

    public ModelCapabilityRegistry(
            @Value("${langchain4j.community.dashscope.chat-model.model-name:qwen-plus}") String modelName,
            @Value("${context.model-context-window:32768}") int contextWindow,
            @Value("${context.model-max-output-tokens:8192}") int maxOutputTokens,
            @Value("${context.tokenizer:compatible-cjk-estimator-v1}") String tokenizer) {
        this.primary = new ModelCapability(
                modelName == null || modelName.isBlank() ? "configured-chat-model" : modelName,
                Math.max(4096, contextWindow),
                Math.max(512, maxOutputTokens),
                tokenizer == null || tokenizer.isBlank() ? "compatible-cjk-estimator-v1" : tokenizer);
    }

    public ModelCapability primary() {
        return primary;
    }

    public record ModelCapability(String modelName, int contextWindow,
                                  int maxOutputTokens, String tokenizer) {
    }
}
