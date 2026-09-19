package com.linkroa.deepdataagent.vault.infrastructure.util;

import com.linkroa.deepdataagent.vault.infrastructure.config.VaultEncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VaultCredentialEncryptionUtil} AES-GCM 加解密单测。
 * <p>验证 {@code [12字节随机IV || 密文]} 载荷的 round-trip：同明文每次加密产生不同密文
 * （随机 IV），但解密后与原文一致；空值 / 非法长度 / 未配置密钥边界行为正确。</p>
 */
class VaultCredentialEncryptionUtilTest {

    private VaultCredentialEncryptionUtil util;

    @BeforeEach
    void setUp() {
        VaultEncryptionProperties properties = new VaultEncryptionProperties();
        properties.setKey("test-vault-key");
        util = new VaultCredentialEncryptionUtil(properties);
    }

    @Test
    void should_roundTripSecret_when_encryptAndDecrypt_given_plainText() {
        // given（中文与特殊字符增强 Unicode 覆盖）
        String plaintext = "sk-秘密-9876!@#";

        // when
        byte[] ciphertext = util.encrypt(plaintext);
        String decrypted = util.decrypt(ciphertext);

        // then
        assertEquals(plaintext, decrypted);
    }

    @Test
    void should_produceDifferentCiphertext_when_encrypt_given_samePlainTextTwice() {
        // given & when（随机 IV：同明文两次加密密文不同，防重放 / 模式分析）
        byte[] first = util.encrypt("sk-plain");
        byte[] second = util.encrypt("sk-plain");

        // then
        assertEquals(first.length, second.length);
        org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(first, second));
    }

    @Test
    void should_containWebLargerThanPlaintext_when_encrypt_given_plainText() {
        // given & when（密码载荷 = 12字节IV + 密文 + 16字节GCM Tag，必然大于原文）
        byte[] ciphertext = util.encrypt("sk-plain");

        // then
        assertEquals(12 + 16 + "sk-plain".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, ciphertext.length);
    }

    @Test
    void should_returnNull_when_encrypt_given_nullPlaintext() {
        // when & then
        assertNull(util.encrypt(null));
    }

    @Test
    void should_returnEmpty_when_encrypt_given_emptyPlaintext() {
        // when & then（空串透传为空字节，落库空密文合法态）
        assertArrayEquals(new byte[0], util.encrypt(""));
    }

    @Test
    void should_returnNull_when_decrypt_given_nullCiphertext() {
        // when & then
        assertNull(util.decrypt(null));
    }

    @Test
    void should_returnEmpty_when_decrypt_given_emptyCiphertext() {
        // when & then
        assertEquals("", util.decrypt(new byte[0]));
    }

    @Test
    void should_throw_when_decrypt_given_shortCiphertext() {
        // when & then（密文长度不大于 12 字节 IV 时无法解出密文段，拒绝）
        assertThrows(IllegalStateException.class, () -> util.decrypt(new byte[] {1, 2, 3}));
    }

    @Test
    void should_throw_when_encrypt_given_missingKey() {
        // given（未配置 vault.encryption.key）
        VaultCredentialEncryptionUtil noKeyUtil = new VaultCredentialEncryptionUtil(new VaultEncryptionProperties());

        // when & then
        assertThrows(IllegalStateException.class, () -> noKeyUtil.encrypt("sk-plain"));
    }
}