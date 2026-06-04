package com.linkroa.deepdataagent.datasource.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogMaskerTest {

    @Test
    void should_maskApiKey_given_textContainsSkKey() {
        // given
        String input = "Error: API Key sk-abc123def456ghi789 is invalid";

        // when
        String result = LogMasker.mask(input);

        // then
        assertEquals("Error: API Key sk-*** is invalid", result);
    }

    @Test
    void should_maskBearerToken_given_textContainsBearer() {
        // given
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secret";

        // when
        String result = LogMasker.mask(input);

        // then
        assertEquals("Authorization: Bearer ***", result);
    }

    @Test
    void should_returnOriginal_given_textContainsNoSensitiveInfo() {
        // given
        String input = "LLM 调用失败: Connection timeout";

        // when
        String result = LogMasker.mask(input);

        // then
        assertEquals(input, result);
    }

    @Test
    void should_returnNull_given_nullInput() {
        // when
        String result = LogMasker.mask(null);

        // then
        assertNull(result);
    }

    @Test
    void should_returnEmpty_given_emptyInput() {
        // when
        String result = LogMasker.mask("");

        // then
        assertEquals("", result);
    }

    @Test
    void should_maskMultipleApiKeys_given_textContainsMultipleKeys() {
        // given
        String input = "key1=sk-abc123, key2=sk-def456";

        // when
        String result = LogMasker.mask(input);

        // then
        assertEquals("key1=sk-***, key2=sk-***", result);
    }

    @Test
    void should_maskBothApiKeyAndBearer_given_textContainsBoth() {
        // given
        String input = "sk-test123 Bearer token456";

        // when
        String result = LogMasker.mask(input);

        // then
        assertEquals("sk-*** Bearer ***", result);
    }

    @Test
    void should_handleApiKeyWithHyphens_given_keyContainsHyphens() {
        // given
        String input = "sk-abc-def-ghi-123";

        // when
        String result = LogMasker.mask(input);

        // then
        assertEquals("sk-***", result);
    }

    @Test
    void should_notMaskPartialSk_given_notStartWithSkDash() {
        // given
        String input = "This is a test sking for fun";

        // when
        String result = LogMasker.mask(input);

        // then
        assertEquals(input, result);
    }

    @Test
    void should_handleBlankString_given_onlyWhitespaces() {
        // given
        String input = "   ";

        // when
        String result = LogMasker.mask(input);

        // then - 空白字符串不包含敏感信息，应原文返回
        assertEquals(input, result);
    }

    @Test
    void should_notMask_given_skDashWithNoWordContent() {
        // given - sk- 后面紧跟空格，没有有效密钥内容
        String input = "prefix sk- suffix";

        // when
        String result = LogMasker.mask(input);

        // then - sk- 后面没有匹配 [\w-]+ 的内容，不应脱敏
        assertEquals(input, result);
    }

    @Test
    void should_handleSpecialRegexChars_given_inputContainsRegexSpecialChars() {
        // given - 包含正则特殊字符的输入
        String input = "Error: .*$^{sk-test123} pattern";

        // when
        String result = LogMasker.mask(input);

        // then - 正则特殊字符不应影响匹配
        assertEquals("Error: .*$^{sk-***} pattern", result);
    }

    @Test
    void should_handleVeryLongText_given_longInputWithApiKey() {
        // given - 极长文本中包含 API Key
        String input = "a".repeat(10000) + " sk-secret123 " + "b".repeat(10000);

        // when
        String result = LogMasker.mask(input);

        // then - 长文本中的 API Key 应被正确脱敏
        assertTrue(result.contains("sk-***"));
        assertFalse(result.contains("sk-secret123"));
    }
}
