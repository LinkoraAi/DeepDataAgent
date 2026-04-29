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
        // given - null key but text looks like encrypted (passes isEncrypted check)
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey(null);
        PasswordEncryptionUtil encryptionUtil = new PasswordEncryptionUtil(properties);

        // when & then - long base64 text will trigger decryption which fails due to null key
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            encryptionUtil.decrypt("dGVzdHRlc3R0ZXN0dGVzdHRlc3R0ZXN0dGVzdHRlc3Q=") // 40 chars base64
        );
        assertEquals("密码解密失败", exception.getMessage());
    }

    @Test
    void should_returnOriginalText_when_decrypt_given_shortBase64() {
        // given - Base64编码但长度小于20（isEncrypted返回false）
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey("test-secret-key-32-bytes-long!!!");
        PasswordEncryptionUtil encryptionUtil = new PasswordEncryptionUtil(properties);

        // short base64 (less than 20 chars) is not considered encrypted
        String shortBase64 = "dGVzdHRlc3Q="; // 12 chars

        // when
        String result = encryptionUtil.decrypt(shortBase64);

        // then - should return original text because isEncrypted returns false
        assertEquals(shortBase64, result);
    }
}
