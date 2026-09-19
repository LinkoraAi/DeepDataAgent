package com.linkroa.deepdataagent.vault.infrastructure.util;

import com.linkroa.deepdataagent.vault.infrastructure.config.VaultEncryptionProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 凭证加密工具（AES/GCM/NoPadding，独立密钥 {@code vault.encryption.key}，与数据源密码、模型凭证密钥隔离）。
 * <p>加密结果为 {@code [12 字节随机 IV || 密文(+128bit GCM Tag)]} 的原始字节，直接以 BYTEA 落
 * {@code vault_credentials.ciphertext} 列；明文仅在本工具调用栈内存中存在，不落库、不进响应与日志。
 * 密钥经 SHA-256 单轮派生（无迭代拉伸），要求密钥本身为高熵随机值（≥32 字节，由 compose 层
 * {@code ${VAR:?}} fail-fast 与 {@link com.linkroa.deepdataagent.vault.infrastructure.config.VaultEncryptionProperties#validate()} 共同保障）。</p>
 */
@Component
public class VaultCredentialEncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final VaultEncryptionProperties encryptionProperties;

    /**
     * 构造器注入独立加密属性（密钥在启动期已由属性校验兜底，此处仅持有引用）。
     *
     * @param encryptionProperties 凭证库加密属性
     */
    public VaultCredentialEncryptionUtil(VaultEncryptionProperties encryptionProperties) {
        this.encryptionProperties = encryptionProperties;
    }

    /**
     * 加密明文并返回 {@code [IV || 密文]} 字节（空串 / 空值原样透传）。
     *
     * @param plaintext 明文（UTF-8）
     * @return 密文字节数组（BYTEA 落库载荷）
     */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext == null ? null : new byte[0];
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(resolveKey(), ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    /**
     * 解密密文字节为明文（空 / 非法长度原样透传）。
     *
     * @param ciphertext {@code [IV || 密文]} 字节（来自 BYTEA 列）
     * @return 解密后的明文（UTF-8）
     */
    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length == 0) {
            return ciphertext == null ? null : "";
        }
        if (ciphertext.length <= GCM_IV_LENGTH) {
            throw new IllegalStateException("凭证密文长度非法，无法解密");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(ciphertext, 0, iv, 0, GCM_IV_LENGTH);
            byte[] encrypted = new byte[ciphertext.length - GCM_IV_LENGTH];
            System.arraycopy(ciphertext, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            SecretKeySpec keySpec = new SecretKeySpec(resolveKey(), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭证解密失败", e);
        }
    }

    /**
     * 由 {@code vault.encryption.key} 经 SHA-256 派生 32 字节 AES-256 密钥（与既有密钥隔离策略一致）。
     */
    private byte[] resolveKey() throws Exception {
        String key = encryptionProperties.getKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("未配置凭证加密密钥(vault.encryption.key)");
        }
        return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    }
}