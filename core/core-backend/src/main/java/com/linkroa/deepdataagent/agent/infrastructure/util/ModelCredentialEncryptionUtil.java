package com.linkroa.deepdataagent.agent.infrastructure.util;

import com.linkroa.deepdataagent.agent.infrastructure.config.ModelEncryptionProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型凭证加密工具（AES/GCM/NoPadding）。
 * <p>与数据源密码加密工具共用算法形态，但使用独立密钥
 * （{@code APP_MODEL_ENCRYPTION_KEY}），实现模型凭证与数据源密码的密钥隔离。</p>
 */
@Component
public class ModelCredentialEncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final ModelEncryptionProperties encryptionProperties;

    public ModelCredentialEncryptionUtil(ModelEncryptionProperties encryptionProperties) {
        this.encryptionProperties = encryptionProperties;
    }

    /**
     * 加密明文（空串/空值原样返回）
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(resolveKey(), ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("模型凭证加密失败", e);
        }
    }

    /**
     * 解密密文（空串/空值/非密文格式原样返回）
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        if (!isEncrypted(ciphertext)) {
            return ciphertext;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            if (decoded.length <= GCM_IV_LENGTH) {
                return ciphertext;
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
            byte[] encrypted = new byte[decoded.length - GCM_IV_LENGTH];
            System.arraycopy(decoded, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            SecretKeySpec keySpec = new SecretKeySpec(resolveKey(), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("模型凭证解密失败", e);
        }
    }

    /**
     * 判断文本是否为加密后的密文格式（Base64）
     */
    public boolean isEncrypted(String text) {
        if (text == null || text.length() < 20) {
            return false;
        }
        try {
            Base64.getDecoder().decode(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] resolveKey() throws Exception {
        String key = encryptionProperties.getKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("未配置模型加密密钥");
        }
        return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    }
}