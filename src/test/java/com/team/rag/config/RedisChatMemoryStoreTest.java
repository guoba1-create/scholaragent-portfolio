package com.team.rag.config;

import com.team.rag.cache.LlmResponseCacheService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatMemoryStoreTest {

    @Test
    void droppedConversationWindowUsesTwoLevelCacheForSummary() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
        when(cache.getOrCompute(anyString(), anyString(), any())).thenReturn("用户关注 STRNN，并要求保留证据。");

        RedisChatMemoryStore store = new RedisChatMemoryStore(redis, chatModel, cache);
        ReflectionTestUtils.setField(store, "redisAvailable", false);
        ReflectionTestUtils.setField(store, "lastRedisCheckTime", System.currentTimeMillis());

        List<ChatMessage> previous = List.of(
                UserMessage.from("什么是 STRNN"),
                AiMessage.from("STRNN 是时空循环网络。"),
                UserMessage.from("需要论文依据"),
                AiMessage.from("我会只使用可追溯证据。"),
                UserMessage.from("保留这个偏好"),
                AiMessage.from("好的"));
        store.updateMessages("conversation-1", previous);

        store.updateMessages("conversation-1", previous.subList(4, 6));

        assertThat(store.getSummary("conversation-1")).contains("STRNN");
        verify(cache).getOrCompute(
                org.mockito.ArgumentMatchers.eq("conversation-summary-v1"),
                anyString(),
                any());
        verify(chatModel, never()).generate(anyString());
    }
}
