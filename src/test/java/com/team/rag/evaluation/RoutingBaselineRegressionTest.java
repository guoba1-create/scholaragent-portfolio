package com.team.rag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.routing.ExecutionCatalogService;
import com.team.rag.agent.routing.ExecutionDecision;
import com.team.rag.agent.routing.ExecutionDecisionService;
import com.team.rag.agent.routing.HybridRouterService;
import com.team.rag.cache.LlmResponseCacheService;
import com.team.rag.service.PromptTemplateService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deterministic routing regression baseline. It executes the real rule engine
 * and the real ExecutionDecision mapping; only the remote LLM response is a
 * fixed fixture so the test remains offline and reproducible.
 */
class RoutingBaselineRegressionTest {

    @Test
    void versionedFiftySampleSuiteMatchesTheAuthoritativeDecisionService() throws Exception {
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        PromptTemplateService prompts = mock(PromptTemplateService.class);
        LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
        when(prompts.render(anyString(), anyString(), anyString())).thenAnswer(call -> call.getArgument(2));
        when(cache.getOrCompute(anyString(), anyString(), any()))
                .thenReturn("{\"intent\":\"paper_qa\",\"confidence\":0.92,\"reason\":\"offline fixture\"}");

        ExecutionDecisionService service = new ExecutionDecisionService(
                new HybridRouterService(chatModel, prompts, cache),
                new ExecutionCatalogService());
        ObjectMapper mapper = new ObjectMapper();
        int total = 0;
        int correctIntent = 0;
        int correctStrategy = 0;
        List<String> mismatches = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/evaluation/agent-eval-v1.jsonl"),
                StandardCharsets.UTF_8))) {
            String row;
            while ((row = reader.readLine()) != null) {
                if (row.isBlank()) continue;
                JsonNode sample = mapper.readTree(row);
                ExecutionDecision decision = service.decide(sample.path("query").asText(), "auto", "auto");
                total++;
                if (sample.path("expectedIntent").asText().equals(decision.intent.name())) correctIntent++;
                if (sample.path("expectedStrategy").asText().equals(decision.strategy.name())) correctStrategy++;
                if (!sample.path("expectedIntent").asText().equals(decision.intent.name())
                        || !sample.path("expectedStrategy").asText().equals(decision.strategy.name())) {
                    mismatches.add(sample.path("id").asText() + ": "
                            + decision.intent + "/" + decision.strategy);
                }
            }
        }

        assertThat(total).isEqualTo(50);
        assertThat(mismatches).isEmpty();
        assertThat(correctIntent).isEqualTo(total);
        assertThat(correctStrategy).isEqualTo(total);
    }
}
