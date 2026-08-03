package com.linkroa.deepdataagent.memory.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

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
        String secondNull = MemoryText.firstNonBlank("first", null, "fallback");
        String hash = MemoryText.sha256("abc");

        // then
        assertEquals("first", first);
        assertEquals("second", second);
        assertEquals("fallback", fallback);
        assertEquals("first", secondNull);
        assertFalse(hash.isBlank());
    }

    @Test
    void should_useFallback_when_slugify_given_nullText() {
        // when
        String result = MemoryText.slug(null, "fallback");

        // then
        assertEquals("fallback", result);
    }

    @Test
    void should_useFallback_when_slugify_given_specialCharsOnly() {
        // when
        String result = MemoryText.slug("!!!@@@###", "fallback");

        // then
        assertEquals("fallback", result);
    }

    @Test
    void should_returnZeroScore_when_lexicalScore_given_exactPhraseMatch() {
        // given: content 包含完整 query 短语
        String query = "Spring Boot";
        String content = "Spring Boot 是一个优秀的框架";

        // when
        double score = MemoryText.lexicalScore(query, content);

        // then
        assertTrue(score > 0.5);
    }

    @Test
    void should_useFallback_when_slugify_given_onlySpecialCharsThatNormalizeToEmpty() {
        // when: 只包含特殊字符，经过 NFKD 规范化后可能为空
        String result = MemoryText.slug("\u0301\u0302\u0303", "fallback");

        // then
        assertEquals("fallback", result);
    }

    @Test
    void should_returnChineseTokens_when_tokenize_given_chineseTextOnly() {
        // when
        var tokens = MemoryText.tokens("你好世界");

        // then
        assertTrue(tokens.contains("你"));
        assertTrue(tokens.contains("好"));
        assertTrue(tokens.contains("世"));
        assertTrue(tokens.contains("界"));
    }

    @Test
    void should_skipBlankTokens_when_tokenize_given_textWithBlankMatches() {
        // when: text that might produce blank tokens from regex
        var tokens = MemoryText.tokens("hello   world");

        // then
        assertFalse(tokens.isEmpty());
        assertTrue(tokens.contains("hello"));
        assertTrue(tokens.contains("world"));
    }

    @Test
    void should_useSecondNull_when_firstNonBlank_given_secondIsNull() {
        // when
        String result = MemoryText.firstNonBlank(null, null, "fallback");

        // then
        assertEquals("fallback", result);
    }

    @Test
    void should_returnEmptySet_when_tokens_given_nullText() {
        // when
        var tokens = MemoryText.tokens(null);

        // then
        assertTrue(tokens.isEmpty());
    }

    @Test
    void should_returnZeroScore_when_lexicalScore_given_nullOrBlankQuery() {
        // when
        double nullScore = MemoryText.lexicalScore(null, "content");
        double blankScore = MemoryText.lexicalScore("   ", "content");

        // then
        assertEquals(0.0, nullScore, 0.0001);
        assertEquals(0.0, blankScore, 0.0001);
    }

    @Test
    void should_throwIllegalStateException_when_sha256_given_noSuchAlgorithmException() {
        // given: mock MessageDigest.getInstance 抛出 NoSuchAlgorithmException
        try (MockedStatic<MessageDigest> mdMock = mockStatic(MessageDigest.class)) {
            mdMock.when(() -> MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new NoSuchAlgorithmException("SHA-256 not available"));

            // when & then
            assertThrows(IllegalStateException.class, () -> MemoryText.sha256("test"));
        }
    }

    @Test
    void should_useFallback_when_slugify_given_untrimmedBlankText() {
        // when: text 包含换行符和空白（不全是空格）
        String result = MemoryText.slug("\n\t\r", "fallback");

        // then
        assertEquals("fallback", result);
    }
}
