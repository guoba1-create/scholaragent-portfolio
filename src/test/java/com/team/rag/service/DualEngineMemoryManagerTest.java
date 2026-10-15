package com.team.rag.service;

import com.team.rag.memory.MemoryLifecycleStatus;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DualEngineMemoryManagerTest {

    @Test
    void recallsAllActiveMemoryTypesButExcludesCandidates() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        Embedding embedding = Embedding.from(new float[]{1.0f, 0.0f});
        when(model.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));

        TextSegment preference = segment("Prefer concise answers", Map.of(
                "memoryId", "mem-active",
                "userId", "u1",
                "type", "user_preference",
                "lifecycleStatus", "ACTIVE"));
        TextSegment candidate = segment("A transient question", Map.of(
                "memoryId", "mem-candidate",
                "userId", "u1",
                "type", "research_question",
                "lifecycleStatus", "CANDIDATE"));
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(
                new EmbeddingMatch<>(0.92, "emb-active", embedding, preference),
                new EmbeddingMatch<>(0.95, "emb-candidate", embedding, candidate))));

        DualEngineMemoryManager manager = new DualEngineMemoryManager(redis, model, store);
        List<Map<String, Object>> memories = manager.searchSemanticMemories("u1", "preference", 10, 0.0);

        assertThat(memories).hasSize(1);
        assertThat(memories.get(0).get("content")).isEqualTo("Prefer concise answers");
        Map<?, ?> metadata = (Map<?, ?>) memories.get(0).get("metadata");
        assertThat(metadata.get("type")).isEqualTo("user_preference");
    }

    @Test
    void disabledLongTermMemoryDoesNotCallEmbeddingBackend() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        DualEngineMemoryManager manager = new DualEngineMemoryManager(redis, model, store);

        manager.setLongTermMemoryEnabled("u1", false);
        List<Map<String, Object>> memories = manager.searchSemanticMemories("u1", "anything", 5, 0.0);

        assertThat(memories).isEmpty();
        verify(model, never()).embed(any(TextSegment.class));
        verify(store, never()).search(any());
    }

    @Test
    void storesLifecycleMetadataAndKeepsEmbeddingIdForDeletion() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        Embedding embedding = Embedding.from(new float[]{0.5f, 0.5f});
        when(model.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));
        when(store.add(any(Embedding.class), any(TextSegment.class))).thenReturn("emb-1");
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
        DualEngineMemoryManager manager = new DualEngineMemoryManager(redis, model, store);

        String memoryId = manager.saveSemanticMemory(
                "u1", "A possible research topic", "space-1", "c1", "wf-1",
                "research_question", "private", 0.65, MemoryLifecycleStatus.CANDIDATE);
        boolean deleted = manager.deleteSemanticMemory("u1", memoryId);

        assertThat(memoryId).startsWith("mem_");
        assertThat(deleted).isTrue();
        verify(store).remove("emb-1");
    }

    private TextSegment segment(String text, Map<String, String> metadata) {
        return TextSegment.from(text, Metadata.from(metadata));
    }
}
