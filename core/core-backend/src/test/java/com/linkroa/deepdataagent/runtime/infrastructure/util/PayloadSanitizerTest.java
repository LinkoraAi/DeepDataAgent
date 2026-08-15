package com.linkroa.deepdataagent.runtime.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayloadSanitizer} 脱敏与截断单测。
 */
class PayloadSanitizerTest {

    @Test
    void should_maskApiKey_when_maskSecrets_given_skKey() {
        // given
        String text = "调用 key 为 sk-abc123def456，请勿泄露";

        // when
        String masked = PayloadSanitizer.maskSecrets(text);

        // then
        assertEquals("调用 key 为 sk-***，请勿泄露", masked);
    }

    @Test
    void should_maskBearerToken_when_maskSecrets_given_bearerHeader() {
        // given
        String text = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.token-value";

        // when
        String masked = PayloadSanitizer.maskSecrets(text);

        // then
        assertEquals("Authorization: Bearer ***", masked);
    }

    @Test
    void should_returnNull_when_maskSecrets_given_null() {
        // when
        String masked = PayloadSanitizer.maskSecrets(null);

        // then
        assertNull(masked);
    }

    @Test
    void should_returnEmpty_when_maskSecrets_given_empty() {
        // when
        String masked = PayloadSanitizer.maskSecrets("");

        // then
        assertEquals("", masked);
    }

    @Test
    void should_keepPlainText_when_maskSecrets_given_noSecrets() {
        // given
        String text = "这是一段普通文本，无敏感信息";

        // when
        String masked = PayloadSanitizer.maskSecrets(text);

        // then
        assertEquals(text, masked);
    }

    @Test
    void should_truncate_when_sanitize_given_veryLongText() {
        // given
        // 纯长文本（不含可脱敏关键词），避免脱敏缩短后长度不超限
        String longText = "x".repeat(3000) + "且包含正文填充";

        // when
        String sanitized = PayloadSanitizer.sanitize(longText);

        // then
        assertTrue(sanitized.length() <= 2000 + "...(truncated)".length());
        assertTrue(sanitized.endsWith("...(truncated)"));
    }

    @Test
    void should_keepShortText_when_sanitize_given_shortText() {
        // given
        String text = "短内容";

        // when
        String sanitized = PayloadSanitizer.sanitize(text);

        // then
        assertEquals(text, sanitized);
    }

    @Test
    void should_maskMultipleKeys_when_maskSecrets_given_manyKeys() {
        // given
        String text = "key1=sk-aaa111 key2=sk-bbb222 bearer=Bearer tok333";

        // when
        String masked = PayloadSanitizer.maskSecrets(text);

        // then
        assertEquals("key1=sk-*** key2=sk-*** bearer=Bearer ***", masked);
    }
}