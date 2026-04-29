package com.linkroa.deepdataagent.datasource.infrastructure.util;

import com.linkroa.deepdataagent.datasource.infrastructure.config.EncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordEncryptionUtilTest {

    private PasswordEncryptionUtil encryptionUtil;

    @BeforeEach
    void setUp() {
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKey("test-secret-key-32-bytes-long!!!");
        encryptionUtil = new PasswordEncryptionUtil(properties);
    }

    @Test
    void should_returnEncryptedText_when_encrypt_given_plainPassword() {
        // given
        String plainPassword = "mySecretPassword123";

        // when
        String encrypted = encryptionUtil.encrypt(plainPassword);

        // then
        assertNotNull(encrypted);
        assertNotEquals(plainPassword, encrypted);
        assertTrue(encrypted.length() > plainPassword.length());
    }

    @Test
    void should_returnOriginalPassword_when_decrypt_given_encryptedPassword() {
        // given
        String plainPassword = "mySecretPassword123";
        String encrypted = encryptionUtil.encrypt(plainPassword);

        // when
        String decrypted = encryptionUtil.decrypt(encrypted);

        // then
        assertEquals(plainPassword, decrypted);
    }

    @Test
    void should_returnDifferentCipher_when_encrypt_given_samePasswordTwice() {
        // given
        String plainPassword = "mySecretPassword123";

        // when
        String encrypted1 = encryptionUtil.encrypt(plainPassword);
        String encrypted2 = encryptionUtil.encrypt(plainPassword);

        // then
        assertNotEquals(encrypted1, encrypted2);

        // but both should decrypt to the same password
        assertEquals(plainPassword, encryptionUtil.decrypt(encrypted1));
        assertEquals(plainPassword, encryptionUtil.decrypt(encrypted2));
    }

    @Test
    void should_returnTrue_when_isEncrypted_given_encryptedText() {
        // given
        String encrypted = encryptionUtil.encrypt("password");

        // when
        boolean result = encryptionUtil.isEncrypted(encrypted);

        // then
        assertTrue(result);
    }

    @Test
    void should_returnFalse_when_isEncrypted_given_plainText() {
        // given
        String plainText = "plainPassword123";

        // when
        boolean result = encryptionUtil.isEncrypted(plainText);

        // then
        assertFalse(result);
    }

    @Test
    void should_returnFalse_when_isEncrypted_given_null() {
        // when
        boolean result = encryptionUtil.isEncrypted(null);

        // then
        assertFalse(result);
    }

    @Test
    void should_returnPlainText_when_decrypt_given_plainText() {
        // given
        String plainText = "plainPassword123";

        // when
        String result = encryptionUtil.decrypt(plainText);

        // then
        assertEquals(plainText, result);
    }

    @Test
    void should_returnNull_when_decrypt_given_null() {
        // when
        String result = encryptionUtil.decrypt(null);

        // then
        assertNull(result);
    }

    @Test
    void should_handleEmptyString_when_encrypt_given_emptyPassword() {
        // given
        String emptyPassword = "";

        // when
        String encrypted = encryptionUtil.encrypt(emptyPassword);
        String decrypted = encryptionUtil.decrypt(encrypted);

        // then
        assertEquals(emptyPassword, decrypted);
    }

    @Test
    void should_handleSpecialCharacters_when_encrypt_given_passwordWithSpecialChars() {
        // given
        String passwordWithSpecialChars = "P@ssw0rd!#$%^&*()_+-=[]{}|;':\",./<>?";

        // when
        String encrypted = encryptionUtil.encrypt(passwordWithSpecialChars);
        String decrypted = encryptionUtil.decrypt(encrypted);

        // then
        assertEquals(passwordWithSpecialChars, decrypted);
    }

    @Test
    void should_handleUnicodeCharacters_when_encrypt_given_passwordWithUnicode() {
        // given
        String passwordWithUnicode = "密码123🔐🚀";

        // when
        String encrypted = encryptionUtil.encrypt(passwordWithUnicode);
        String decrypted = encryptionUtil.decrypt(encrypted);

        // then
        assertEquals(passwordWithUnicode, decrypted);
    }

    @Test
    void should_returnFalse_when_isEncrypted_given_shortText() {
        // given
        String shortText = "short";

        // when
        boolean result = encryptionUtil.isEncrypted(shortText);

        // then
        assertFalse(result);
    }

    @Test
    void should_returnFalse_when_isEncrypted_given_invalidBase64() {
        // given
        String invalidBase64 = "!!!not-valid-base64!!!";

        // when
        boolean result = encryptionUtil.isEncrypted(invalidBase64);

        // then
        assertFalse(result);
    }

    @Test
    void should_returnEmptyString_when_encrypt_given_emptyPassword() {
        // given
        String emptyPassword = "";

        // when
        String result = encryptionUtil.encrypt(emptyPassword);

        // then
        assertEquals(emptyPassword, result);
    }

    @Test
    void should_returnNull_when_encrypt_given_nullPassword() {
        // given
        String nullPassword = null;

        // when
        String result = encryptionUtil.encrypt(nullPassword);

        // then
        assertNull(result);
    }

    @Test
    void should_returnOriginalText_when_decrypt_given_shortBase64() {
        // given - Base64编码但长度小于20（isEncrypted返回false）
        String shortBase64 = "dGVzdHRlc3Q="; // 12 chars, less than 20

        // when
        String result = encryptionUtil.decrypt(shortBase64);

        // then - 应该返回原文，因为isEncrypted返回false
        assertEquals(shortBase64, result);
    }

    @Test
    void should_returnOriginalText_when_decrypt_given_nonEncryptedLongText() {
        // given - 非加密的长文本（不是有效的Base64或长度不满足加密格式）
        String longPlainText = "this-is-a-very-long-plain-text-password-that-is-not-encrypted";

        // when
        String result = encryptionUtil.decrypt(longPlainText);

        // then - 应该返回原文
        assertEquals(longPlainText, result);
    }

    @Test
    void should_throwException_when_decrypt_given_invalidEncryptedData() {
        // given - 看起来像加密的Base64（长度>=20）但数据无效
        // 使用有效的Base64但内容不是有效的加密数据
        String invalidEncryptedData = "dGVzdHRlc3R0ZXN0dGVzdHRlc3R0ZXN0dGVzdHRlc3Q="; // 40 chars base64

        // when & then - 应该抛出异常，因为数据格式不正确
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            encryptionUtil.decrypt(invalidEncryptedData)
        );
        assertEquals("密码解密失败", exception.getMessage());
    }
}
