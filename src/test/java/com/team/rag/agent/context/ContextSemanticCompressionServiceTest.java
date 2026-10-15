package com.team.rag.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.cache.LlmResponseCacheService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContextSemanticCompressionServiceTest {

    private final ContextSemanticCompressionService service = new ContextSemanticCompressionService(
            provider(), mock(LlmResponseCacheService.class), new TokenEstimator(),
            new ObjectMapper(), true, "small-model-test");

    @Test
    void acceptsSchemaValidShorterSummaryAndPreservesCitationIds() {
        String original = "已确认 STRNN 建模时空转移 [E1]。仍需核验实验设置 [E2]。".repeat(20);
        String json = """
                {"facts":["STRNN 建模时空转移 [E1]"],
                 "inferences":[],"unknowns":["实验设置待核验 [E2]"],
                 "constraints":[],"errors":[],"nextSteps":[],"citations":["[E1]","[E2]"]}
                """;

        ContextSemanticCompressionService.CompressionResult result =
                service.parseAndValidate(original, json, 200, false);

        assertThat(result.applied()).isTrue();
        assertThat(result.text()).contains("[E1]", "[E2]", "facts:", "unknowns:");
        assertThat(result.modelUsed()).isEqualTo("small-model-test");
    }

    @Test
    void rejectsMissingCitationOrInvalidSchema() {
        String original = "关键证据 [E1] 与 [E2]。".repeat(20);
        String missingCitation = """
                {"facts":["关键证据 [E1]"],"inferences":[],"unknowns":[],
                 "constraints":[],"errors":[],"nextSteps":[],"citations":["[E1]"]}
                """;

        assertThat(service.parseAndValidate(original, missingCitation, 200, false).applied()).isFalse();
        assertThat(service.parseAndValidate(original, "not-json", 200, false).applied()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatLanguageModel> provider() {
        return mock(ObjectProvider.class);
    }
}
