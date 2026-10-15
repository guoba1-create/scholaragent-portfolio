package com.team.rag.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationSecurityTest {

    @Test
    void applicationConfigurationContainsNoCommittedProviderSecrets() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/application.yml")) {
            assertThat(stream).isNotNull();
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content)
                    .doesNotContain("sk-")
                    .doesNotContain("pcsk_")
                    .doesNotContainPattern("bot-token:\\s*[0-9]{6,}:")
                    .contains("${DASHSCOPE_API_KEY:}")
                    .contains("${PINECONE_API_KEY:}")
                    .contains("${TELEGRAM_BOT_TOKEN:}");
        }
    }
}
