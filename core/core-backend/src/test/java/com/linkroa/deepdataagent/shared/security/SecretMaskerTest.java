package com.linkroa.deepdataagent.shared.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretMasker} 脱敏与截断单测。
 */
class SecretMaskerTest {

    @Test
    void should_maskApiKey_when_maskSecrets_given_skKey() {
        // given
        String text = "调用 key 为 sk-abc123def456，请勿泄露";

        // when
        String masked = SecretMasker.maskSecrets(text);

        // then
        assertEquals("调用 key 为 sk-***，请勿泄露", masked);
    }

    @Test
    void should_maskBearerToken_when_maskSecrets_given_bearerHeader() {
        // given
        String text = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.token-value";

        // when
        String masked = SecretMasker.maskSecrets(text);

        // then
        assertEquals("Authorization: Bearer ***", masked);
    }

    @Test
    void should_returnNull_when_maskSecrets_given_null() {
        // when
        String masked = SecretMasker.maskSecrets(null);

        // then
        assertNull(masked);
    }

    @Test
    void should_returnEmpty_when_maskSecrets_given_empty() {
        // when
        String masked = SecretMasker.maskSecrets("");

        // then
        assertEquals("", masked);
    }

    @Test
    void should_keepPlainText_when_maskSecrets_given_noSecrets() {
        // given
        String text = "这是一段普通文本，无敏感信息";

        // when
        String masked = SecretMasker.maskSecrets(text);

        // then
        assertEquals(text, masked);
    }

    @Test
    void should_truncate_when_sanitize_given_veryLongText() {
        // given
        // 纯长文本（不含可脱敏关键词），避免脱敏缩短后长度不超限
        String longText = "x".repeat(3000) + "且包含正文填充";

        // when
        String sanitized = SecretMasker.sanitize(longText);

        // then
        assertTrue(sanitized.length() <= 2000 + "...(truncated)".length());
        assertTrue(sanitized.endsWith("...(truncated)"));
    }

    @Test
    void should_keepShortText_when_sanitize_given_shortText() {
        // given
        String text = "短内容";

        // when
        String sanitized = SecretMasker.sanitize(text);

        // then
        assertEquals(text, sanitized);
    }

    @Test
    void should_maskMultipleKeys_when_maskSecrets_given_manyKeys() {
        // given
        String text = "key1=sk-aaa111 key2=sk-bbb222 bearer=Bearer tok333";

        // when
        String masked = SecretMasker.maskSecrets(text);

        // then
        assertEquals("key1=sk-*** key2=sk-*** bearer=Bearer ***", masked);
    }

    // ==================== maskExactValues：已知明文精确掩码 ====================

    @Test
    void should_maskKnownSecret_when_maskExactValues_given_textContainingPlainToken() {
        // given（形态正则覆盖不了的任意字节 token：ghp_ 前缀 GitHub 风格）
        String text = "工具输出: headers={Authorization: Bearer ghp_9Kd8xLm2QwErTyUiOp}";

        // when
        String masked = SecretMasker.maskExactValues(text, List.of("ghp_9Kd8xLm2QwErTyUiOp"));

        // then（精确值出现处整体替换为 ****，Bearer 形态前缀不受影响）
        assertFalse(masked.contains("ghp_9Kd8xLm2QwErTyUiOp"));
        assertTrue(masked.contains("Authorization: Bearer ****"));
    }

    @Test
    void should_maskAllOccurrences_when_maskExactValues_given_repeatedSecret() {
        // given（同一明文多次出现）
        String text = "a=tok-777 b=tok-777 tok-777";

        // when
        String masked = SecretMasker.maskExactValues(text, List.of("tok-777"));

        // then（全部替换）
        assertEquals("a=**** b=**** ****", masked);
    }

    @Test
    void should_maskEachValue_when_maskExactValues_given_multipleSecrets() {
        // given（本轮挂载多条凭据明文）
        String text = "first=AAA111 second=BBB222";

        // when
        String masked = SecretMasker.maskExactValues(text, List.of("AAA111", "BBB222"));

        // then
        assertEquals("first=**** second=****", masked);
    }

    @Test
    void should_keepText_when_maskExactValues_given_noSecretPresent() {
        // given（已知秘密未出现在文本中）
        String text = "普通工具输出，无凭据回显";

        // when
        String masked = SecretMasker.maskExactValues(text, List.of("zzz-secret"));

        // then（原样返回）
        assertEquals(text, masked);
    }

    @Test
    void should_returnOriginal_when_maskExactValues_given_nullOrEmptyInputs() {
        // given & when & then（text null / 空、knownSecrets null / 空集合，均原样返回）
        assertNull(SecretMasker.maskExactValues(null, List.of("s")));
        assertEquals("", SecretMasker.maskExactValues("", List.of("s")));
        String text = "text";
        assertEquals(text, SecretMasker.maskExactValues(text, null));
        assertEquals(text, SecretMasker.maskExactValues(text, List.of()));
    }

    @Test
    void should_skipBlankSecret_when_maskExactValues_given_blankElements() {
        // given（空白凭据值混入——空串会把整篇文本打碎，必须跳过；Arrays.asList 容忍 null）
        String text = "abc";

        // when
        String masked = SecretMasker.maskExactValues(text, Arrays.asList("", "  ", null));

        // then（原文完好）
        assertEquals("abc", masked);
    }

    @Test
    void should_maskBareJwt_when_maskExactValues_given_plaintextWithoutBearerPrefix() {
        // given（裸 JWT：形态正则既不认 sk- 前缀、也没有 Bearer 前缀，只按已知明文精确掩码兜底）
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLTEifQ.c2lnbmF0dXJlLWJ5dGVz";
        String text = "工具回显 token=" + jwt + " 结束";

        // when（先证明形态脱敏覆盖不到它，再走精确掩码）
        String patternOnly = SecretMasker.maskSecrets(text);

        // then（形态脱敏原样返回；精确掩码后明文零残留）
        assertEquals(text, patternOnly);
        String masked = SecretMasker.maskExactValues(text, List.of(jwt));
        assertFalse(masked.contains(jwt));
        assertEquals("工具回显 token=**** 结束", masked);
    }

    @Test
    void should_maskAfterPatternMask_when_maskSecrets_then_maskExactValues_given_bearerFormSecret() {
        // given（先形态脱敏、再精确掩码的串联使用：sk- 形态已被压成 sk-***，
        // 剩余任意明文由精确掩码兜底）
        String text = "sk-abc123 raw-xyz-999";

        // when
        String masked = SecretMasker.maskExactValues(
                SecretMasker.maskSecrets(text), List.of("raw-xyz-999"));

        // then（两类掩码标记各归其位：形态 *** 与精确 ****）
        assertEquals("sk-*** ****", masked);
    }
}