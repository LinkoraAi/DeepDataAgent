package com.linkroa.deepdataagent.datasource.infrastructure.util;

import com.linkroa.deepdataagent.datasource.infrastructure.config.EncryptionProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordEncryptionUtilEdgeCaseTest {

    @Test
    void should_throwException_when_encrypt_given_nullKey() {
        // given
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey(null);
        PasswordEncryptionUtil encryptionUtil = new PasswordEncryptionUtil(properties);

        // when & then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            encryptionUtil.encrypt("password")
        );
        assertEquals("密码加密失败", exception.getMessage());
    }

    @Test
    void should_throwException_when_encrypt_given_blankKey() {
        // given
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey("   ");
        PasswordEncryptionUtil encryptionUtil = new PasswordEncryptionUtil(properties);

        // when & then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            encryptionUtil.encrypt("password")
        );
        assertEquals("密码加密失败", exception.getMessage());
    }

    @Test
    void should_returnOriginalText_when_decrypt_given_nullKey_and_nonEncryptedText() {
        // given - null key but text is not encrypted (isEncrypted returns false)
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey(null);
        PasswordEncryptionUtil encryptionUtil = new PasswordEncryptionUtil(properties);

        // when - short text is not considered encrypted
        String result = encryptionUtil.decrypt("short");

        // then - should return original text without throwing exception
        assertEquals("short", result);
    }

    @Test
    void should_throwException_when_decrypt_given_nullKey_and_encryptedText() {
        // given - null key but text looks like encrypted (passes isEncrypted check, 带密文前缀)
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey(null);
        PasswordEncryptionUtil encryptionUtil = new PasswordEncryptionUtil(properties);

        // when & then - 带前缀文本会触发解密流程，因 null key 解密失败
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            encryptionUtil.decrypt(PasswordEncryptionUtil.ENCRYPTED_PREFIX
                    + "dGVzdHRlc3R0ZXN0dGVzdHRlc3R0ZXN0dGVzdHRlc3Q=") // 带前缀的 40 chars base64
        );
        assertEquals("密码解密失败", exception.getMessage());
    }

    @Test
    void should_returnOriginalText_when_decrypt_given_nullKey_and_prefixedInvalidBase64() {
        // given - null key 且密文负载为非法 Base64（解密流程捕获解码异常）
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey(null);
        PasswordEncryptionUtil encryptionUtil = new PasswordEncryptionUtil(properties);

        // when & then - 非法负载同样抛出解密失败
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            encryptionUtil.decrypt(PasswordEncryptionUtil.ENCRYPTED_PREFIX + "!!!not-valid-base64!!!")
        );
        assertEquals("密码解密失败", exception.getMessage());
    }

    @Test
    void should_returnOriginalText_when_decrypt_given_shortBase64() {
        // given - Base64编码但无密文前缀（isEncrypted返回false）
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey("test-secret-key-32-bytes-long!!!");
        PasswordEncryptionUtil encryptionUtil = new PasswordEncryptionUtil(properties);

        // short base64 (无前缀) is not considered encrypted
        String shortBase64 = "dGVzdHRlc3Q="; // 12 chars

        // when
        String result = encryptionUtil.decrypt(shortBase64);

        // then - should return original text because no prefix
        assertEquals(shortBase64, result);
    }
}
