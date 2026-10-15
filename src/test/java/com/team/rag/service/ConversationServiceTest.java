package com.team.rag.service;

import com.team.rag.entity.Conversation;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    @Test
    void appendsMessageWithSingleAtomicRedisScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("conversation:u1:c1")).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        ConversationService service = new ConversationService(redis);

        service.addMessage("u1", "c1", "user", "hello");

        verify(redis).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(redis, never()).opsForList();
        verify(redis, never()).opsForZSet();
    }

    @Test
    void hydratesMetadataWithOrderedAppendOnlyMessages() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForList()).thenReturn(lists);
        when(values.get("conversation:u1:c1")).thenReturn("""
                {"id":"c1","userId":"u1","title":"Demo","messages":[],
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}
                """);
        when(lists.range("conversation:messages:u1:c1", 0, -1)).thenReturn(List.of(
                "{\"sequence\":1,\"role\":\"user\",\"content\":\"Q\",\"timestamp\":\"2026-01-01T00:00:01Z\"}",
                "{\"sequence\":2,\"role\":\"assistant\",\"content\":\"A\",\"timestamp\":\"2026-01-01T00:00:02Z\"}"));
        ConversationService service = new ConversationService(redis);

        Conversation conversation = service.getConversation("u1", "c1");

        assertThat(conversation).isNotNull();
        assertThat(conversation.getMessages()).extracting(Conversation.Message::getContent)
                .containsExactly("Q", "A");
        assertThat(conversation.getUpdatedAt()).hasToString("2026-01-01T00:00:02Z");
    }
}
