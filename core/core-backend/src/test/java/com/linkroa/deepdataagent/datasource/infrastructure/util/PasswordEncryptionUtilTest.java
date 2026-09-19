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
        assertTrue(encrypted.startsWith(PasswordEncryptionUtil.ENCRYPTED_PREFIX));
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
        // given - Base64编码但无密文前缀（isEncrypted返回false）
        String shortBase64 = "dGVzdHRlc3Q="; // 12 chars

        // when
        String result = encryptionUtil.decrypt(shortBase64);

        // then - 应该返回原文，因为无前缀视为明文
        assertEquals(shortBase64, result);
    }

    @Test
    void should_returnOriginalText_when_decrypt_given_nonEncryptedLongText() {
        // given - 非加密的长文本（无密文前缀）
        String longPlainText = "this-is-a-very-long-plain-text-password-that-is-not-encrypted";

        // when
        String result = encryptionUtil.decrypt(longPlainText);

        // then - 应该返回原文
        assertEquals(longPlainText, result);
    }

    @Test
    void should_returnOriginalText_when_decrypt_given_longValidBase64PlainText() {
        // given - 明文恰好是合法且较长的 Base64（旧版启发式会误判为密文），但无密文前缀
        String longBase64PlainText = "dGVzdHRlc3R0ZXN0dGVzdHRlc3R0ZXN0dGVzdHRlc3Q="; // 40 chars, valid base64

        // when
        String result = encryptionUtil.decrypt(longBase64PlainText);

        // then - 显式前缀判定：无前缀一律透传，不再误判
        assertEquals(longBase64PlainText, result);
    }

    @Test
    void should_throwException_when_decrypt_given_invalidEncryptedData() {
        // given - 带密文前缀但内容不是有效的加密数据
        // 使用有效的Base64但内容不是有效的加密数据（解码后无法通过 GCM 验签）
        String invalidEncryptedData = PasswordEncryptionUtil.ENCRYPTED_PREFIX
                + "dGVzdHRlc3R0ZXN0dGVzdHRlc3R0ZXN0dGVzdHRlc3Q="; // 40 chars base64

        // when & then - 应该抛出异常，因为数据格式不正确
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            encryptionUtil.decrypt(invalidEncryptedData)
        );
        assertEquals("密码解密失败", exception.getMessage());
    }

    @Test
    void should_returnPlainText_when_decrypt_given_legacyCiphertextWithoutPrefix() {
        // given - 旧格式密文：无前缀 Base64(iv||密文)，与新版唯一差异是缺 dse: 前缀（算法/密钥同源）
        String plainPassword = "legacyStoredP@ss";
        String legacyCiphertext = encryptionUtil.encrypt(plainPassword)
                .substring(PasswordEncryptionUtil.ENCRYPTED_PREFIX.length());

        // when - 过渡双读：命中旧启发式（长度≥20 且合法 Base64）按旧格式解密
        String decrypted = encryptionUtil.decrypt(legacyCiphertext);

        // then
        assertEquals(plainPassword, decrypted);
    }

    @Test
    void should_passThroughOriginal_when_decrypt_given_legacyHeuristicMatchButDecryptFailed() {
        // given - 真正的历史明文：恰好命中旧启发式（合法 Base64 且长度≥20）但无法通过 GCM 验签
        String legacyPlainText = "dGVzdHRlc3R0ZXN0dGVzdHRlc3R0ZXN0dGVzdHRlc3Q="; // 40 chars valid base64

        // when - 双读尝试解密失败（认证标签异常等）→ 按明文透传、不抛异常
        String result = encryptionUtil.decrypt(legacyPlainText);

        // then
        assertEquals(legacyPlainText, result);
    }

    @Test
    void should_returnSameValue_when_encrypt_given_alreadyEncryptedCiphertext() {
        // given - 已带 dse: 前缀的密文（如实体回读后再次经转换层）
        String encrypted = encryptionUtil.encrypt("mySecretPassword123");

        // when - 幂等守卫：不重复加密，防双重化
        String reEncrypted = encryptionUtil.encrypt(encrypted);

        // then - 原样返回，且仍可解出真实明文
        assertEquals(encrypted, reEncrypted);
        assertEquals("mySecretPassword123", encryptionUtil.decrypt(reEncrypted));
    }
}
