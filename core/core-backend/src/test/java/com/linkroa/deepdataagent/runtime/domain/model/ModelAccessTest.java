package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelAccess} 模型访问配置单测
 * <p>覆盖工厂方法与明文凭证脱敏（toString 不泄露 apiKey 全文）。</p>
 */
class ModelAccessTest {

    @Test
    void should_maskApiKey_when_toString_given_plainCredential() {
        // given（含明文凭证的模型访问配置）
        ModelAccess access = ModelAccess.of("sk-plain-secret", "https://api.example.com/v1");

        // when
        String text = access.toString();

        // then（明文凭证不得出现在 toString 中，脱敏后仍保留前缀可定位）
        assertFalse(text.contains("sk-plain-secret"));
        assertTrue(text.contains("sk-p****"));
    }

    @Test
    void should_keepFields_when_of_given_credentialAndEndpoint() {
        // given & when
        ModelAccess access = ModelAccess.of("sk-plain", "https://api.example.com/v1");

        // then
        assertEquals("sk-plain", access.apiKey());
        assertEquals("https://api.example.com/v1", access.baseUrl());
    }

    @Test
    void should_maskFully_when_toString_given_shortCredential() {
        // given（长度不足保留阈值，应整体掩码）
        ModelAccess access = ModelAccess.of("abcd", null);

        // when
        String text = access.toString();

        // then
        assertFalse(text.contains("abcd"));
        assertTrue(text.contains("****"));
    }
}