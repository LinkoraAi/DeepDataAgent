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

    @Test
    void should_extractEpisodicOnly_when_transcriptIsBlank() {
        ConversationContext context = new ConversationContext(
                "session-blank",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertTrue(memories.isEmpty());
    }

    @Test
    void should_extractEpisodicOnly_when_messageIsFromAssistant() {
        ConversationContext context = new ConversationContext(
                "session-assistant",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("assistant", "assistant", "你好，有什么可以帮助你的？", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertEquals(1, memories.size());
        assertTrue(memories.stream().allMatch(m -> "episodic".equals(m.layer())));
    }

    @Test
    void should_extractNoMemory_when_userMessageHasBlankText() {
        ConversationContext context = new ConversationContext(
                "session-blank-text",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "   ", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertTrue(memories.isEmpty());
    }

    @Test
    void should_extractPreferenceMemory_when_userExpressesDislike() {
        ConversationContext context = new ConversationContext(
                "session-dislike",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "我不喜欢 XML 配置，请记住。", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertTrue(memories.stream().anyMatch(m ->
                "semantic".equals(m.layer()) && "preference".equals(m.subCategory())));
    }

    @Test
    void should_omitAssistantSummary_when_assistantTextIsBlank() {
        ConversationContext context = new ConversationContext(
                "session-no-assistant",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "测试消息", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertEquals(1, memories.size());
        String content = memories.get(0).content();
        assertFalse(content.contains("最近回复要点"));
    }

    @Test
    void should_extractFactMemory_when_userExpressesRememberWithoutPreference() {
        ConversationContext context = new ConversationContext(
                "session-remember",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "请记住：我的数据库密码是 123456。", null),
                        new ConversationMessage("assistant", "assistant", "已记录。", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertTrue(memories.stream().anyMatch(m ->
                "semantic".equals(m.layer()) && "fact".equals(m.subCategory())));
    }

    @Test
    void should_extractEpisodicOnly_when_allMessagesAreFromAssistant() {
        ConversationContext context = new ConversationContext(
                "session-all-assistant",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("assistant", "assistant", "你好", null),
                        new ConversationMessage("assistant", "assistant", "有什么可以帮助你的？", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertEquals(1, memories.size());
        assertTrue(memories.stream().allMatch(m -> "episodic".equals(m.layer())));
    }

    @Test
    void should_extractPreferenceMemory_when_userMessageContainsBothRememberAndPreference() {
        ConversationContext context = new ConversationContext(
                "session-both",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "请记住：我偏好使用 Redis 缓存。", null),
                        new ConversationMessage("assistant", "assistant", "已记录。", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertTrue(memories.stream().anyMatch(m ->
                "semantic".equals(m.layer()) && "preference".equals(m.subCategory())));
    }

    @Test
    void should_usePreferenceImportance_when_userExpressesPreferenceWithoutExplicitRemember() {
        ConversationContext context = new ConversationContext(
                "session-preference-only",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "我偏好使用 Redis 缓存。", null),
                        new ConversationMessage("assistant", "assistant", "已记录。", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertTrue(memories.stream().anyMatch(m ->
                "semantic".equals(m.layer()) && "preference".equals(m.subCategory())));
        assertTrue(memories.stream().anyMatch(m -> Math.abs(m.importance() - 0.72) < 0.001));
    }

    @Test
    void should_useFactImportance_when_userExpressesRememberWithoutPreference() {
        ConversationContext context = new ConversationContext(
                "session-fact-only",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "请记住：我的数据库密码是 123456。", null),
                        new ConversationMessage("assistant", "assistant", "已记录。", null)
                )
        );

        List<ExtractedMemory> memories = extractor.extractAndClassify(context);

        assertTrue(memories.stream().anyMatch(m ->
                "semantic".equals(m.layer()) && "fact".equals(m.subCategory())));
        assertTrue(memories.stream().anyMatch(m -> Math.abs(m.importance() - 0.9) < 0.001));
    }

    @Test
    void should_shortenLongText_when_shorten_given_exceedingMaxLength() {
        String longText = "a".repeat(100);

        // Use reflection to call private static method
        try {
            java.lang.reflect.Method method = FallbackMemoryExtractor.class.getDeclaredMethod("shorten", String.class, int.class);
            method.setAccessible(true);
            String shortened = (String) method.invoke(null, longText, 10);
            assertEquals("aaaaaaa...", shortened);
        } catch (Exception e) {
            fail("Failed to invoke shorten method: " + e.getMessage());
        }
    }

    @Test
    void should_returnEmpty_when_shorten_given_nullValue() {
        try {
            java.lang.reflect.Method method = FallbackMemoryExtractor.class.getDeclaredMethod("shorten", String.class, int.class);
            method.setAccessible(true);
            String result = (String) method.invoke(null, null, 10);
            assertEquals("", result);
        } catch (Exception e) {
            fail("Failed to invoke shorten method: " + e.getMessage());
        }
    }
}
