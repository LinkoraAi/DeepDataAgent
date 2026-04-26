package com.linkroa.deepdataagent.memory.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MemoryTextTest {

    @Test
    void should_returnChineseAndAsciiTokens_when_tokenizeAndNormalize_given_mixedLanguageText() {
        // given
        String text = "Spring Boot 配置 YAML-Only";

        // when
        var tokens = MemoryText.tokens(text);

        // then
        assertTrue(tokens.contains("spring"));
        assertTrue(tokens.contains("boot"));
        assertTrue(tokens.contains("配"));
        assertTrue(tokens.contains("置"));
        assertTrue(tokens.contains("yaml-only"));
    }

    @Test
    void should_returnHigherLexicalScoreAndHandleBlankInput_when_lexicalScore_given_matchingAndBlankQueries() {
        // given
        String matchingContent = "用户偏好 YAML 配置，不使用 XML。";

        // when
        double blankQueryScore = MemoryText.lexicalScore("", "Spring Boot YAML");
        double blankContentScore = MemoryText.lexicalScore("Redis", "");
        double strong = MemoryText.lexicalScore("YAML 配置", matchingContent);
        double weak = MemoryText.lexicalScore("Redis 缓存", matchingContent);

        // then
        assertEquals(0.0, blankQueryScore, 0.0001);
        assertEquals(0.0, blankContentScore, 0.0001);
        assertTrue(strong > weak);
        assertTrue(strong > 0.5);
    }

    @Test
    void should_useFallbackAndLimitLength_when_slugify_given_blankAndLongText() {
        // given
        String blankTitle = "   ";
        String mixedTitle = "Spring Boot YAML 配置";
        String longTitle = "a".repeat(80);

        // when
        String blankSlug = MemoryText.slug(blankTitle, "fallback");
        String mixedSlug = MemoryText.slug(mixedTitle, "fallback");
        String longSlug = MemoryText.slug(longTitle, "fallback");

        // then
        assertEquals("fallback", blankSlug);
        assertEquals("spring-boot-yaml-配置", mixedSlug);
        assertEquals(48, longSlug.length());
    }

    @Test
    void should_returnFirstAvailableValue_when_firstNonBlank_given_multipleCandidates() {
        // given
        String firstCandidate = " first ";
        String secondCandidate = " second ";

        // when
        String first = MemoryText.firstNonBlank(firstCandidate, "second", "fallback");
        String second = MemoryText.firstNonBlank(" ", secondCandidate, "fallback");
        String fallback = MemoryText.firstNonBlank(null, " ", "fallback");
        String hash = MemoryText.sha256("abc");

        // then
        assertEquals("first", first);
        assertEquals("second", second);
        assertEquals("fallback", fallback);
        assertFalse(hash.isBlank());
    }
}
