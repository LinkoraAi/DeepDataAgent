package com.linkroa.deepdataagent.memory.extractor;

import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ConversationContext.ConversationMessage;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FallbackMemoryExtractorTest {

    private final FallbackMemoryExtractor extractor = new FallbackMemoryExtractor();

    @Test
    void should_extractEpisodicMemory_when_givenAnyConversation() {
        ConversationContext context = new ConversationContext(
                "session-test",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "你好", null),
                        new ConversationMessage("assistant", "assistant", "你好，有什么可以帮助你的？", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertEquals(1, memories.size());
        assertEquals("episodic", memories.get(0).layer());
    }

    @Test
    void should_extractPreferenceMemory_when_userExpressesPreference() {
        ConversationContext context = new ConversationContext(
                "session-test",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "请记住：我偏好 Spring Boot YAML 配置。", null),
                        new ConversationMessage("assistant", "assistant", "已记录。", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertTrue(memories.stream().anyMatch(m -> "episodic".equals(m.layer())));
        assertTrue(memories.stream().anyMatch(m ->
                "semantic".equals(m.layer()) && "preference".equals(m.subCategory())));
    }

    @Test
    void should_notExtractSemanticMemory_when_noSignalWords() {
        ConversationContext context = new ConversationContext(
                "session-chat",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "你好，今天天气怎么样？", null),
                        new ConversationMessage("assistant", "assistant", "天气很好。", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertEquals(1, memories.size());
        assertTrue(memories.stream().allMatch(m -> "episodic".equals(m.layer())));
    }
}
