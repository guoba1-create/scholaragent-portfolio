package com.team.rag.health;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeReadinessServiceTest {

    @Test
    void reportsReadyWithConfiguredCoreAndOptionalServicesDown() {
        MockEnvironment environment = baseEnvironment();
        RuntimeReadinessService service = new RuntimeReadinessService(environment, redis("PONG"));

        RuntimeReadinessService.ReadinessReport report = service.check();

        assertThat(report.ready()).isTrue();
        assertThat(report.status()).isEqualTo("DEGRADED");
    }

    @Test
    void productionGatewayRejectsDevelopmentRuntimeToken() {
        MockEnvironment environment = baseEnvironment()
                .withProperty("app.identity.mode", "gateway")
                .withProperty("app.identity.gateway-token", "gateway-secret");
        RuntimeReadinessService service = new RuntimeReadinessService(environment, redis("PONG"));

        RuntimeReadinessService.ReadinessReport report = service.check();

        assertThat(report.ready()).isFalse();
        assertThat(report.components()).anySatisfy(component -> {
            assertThat(component.name()).isEqualTo("runtime-auth");
            assertThat(component.status()).isEqualTo("DOWN");
        });
    }

    private MockEnvironment baseEnvironment() {
        return new MockEnvironment()
                .withProperty("langchain4j.community.dashscope.chat-model.api-key", "configured")
                .withProperty("pinecone.api-key", "configured")
                .withProperty("agent.internal.token", "dev-agent-runtime-token-change-me")
                .withProperty("app.identity.mode", "dev")
                .withProperty("agent.runtime.python.enabled", "false")
                .withProperty("agent.queue.backend", "local")
                .withProperty("document.ingest.kafka.enabled", "false")
                .withProperty("spring.kafka.bootstrap-servers", "localhost:1")
                .withProperty("team-rag.grobid.enabled", "false")
                .withProperty("agent.search.searxng-url", "http://localhost:1");
    }

    private RedisConnectionFactory redis(String ping) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn(ping);
        return factory;
    }
}
