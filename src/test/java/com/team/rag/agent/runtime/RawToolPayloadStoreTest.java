package com.team.rag.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RawToolPayloadStoreTest {

    @Test
    void storesFullPayloadOutsideObservationAndRetainsWorkflowOwnership() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RawToolPayloadStore store = new RawToolPayloadStore(new ObjectMapper().findAndRegisterModules(), redis);

        String ref = store.store("wf-1", "retrieve-evidence", Map.of("content", "x".repeat(25_000)));
        RawToolPayload loaded = store.find(ref).orElseThrow();

        assertThat(ref).startsWith("redis://tool-payload/payload_");
        assertThat(loaded.workflowId).isEqualTo("wf-1");
        assertThat(loaded.toolName).isEqualTo("retrieve_evidence");
        assertThat(loaded.payload).hasSizeGreaterThan(25_000);
        assertThat(loaded.sha256).hasSize(64);
        verify(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void rejectsNonCanonicalReferences() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RawToolPayloadStore store = new RawToolPayloadStore(new ObjectMapper().findAndRegisterModules(), redis);

        assertThat(store.find("payload:sha256:abc")).isEmpty();
        assertThat(store.find("redis://tool-payload/../../other")).isEmpty();
    }
}
