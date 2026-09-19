package com.linkroa.deepdataagent.datasource.infrastructure.util;

import com.linkroa.deepdataagent.datasource.infrastructure.config.EncryptionProperties;
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
 * 数据源凭证 AES-GCM 加解密工具（密钥 {@code datasource.encryption.key}）。
 * <p>新密文统一携带显式前缀 {@link #ENCRYPTED_PREFIX}；解密侧保留对旧格式
 * （无前缀 Base64，旧「Base64 + 长度≥20」启发式产物）的<b>过渡期双读</b>：
 * 命中旧启发式则先按旧格式尝试解密，成功返回明文、失败按历史明文透传。
 * 双读仅为兼容存量密文，新写入一律走 {@link #encrypt(String)} 产出带前缀格式；
 * 存量数据的格式迁移按 BREAKING 政策另行立项处理。</p>
 */
@Component
public class PasswordEncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /** 旧格式密文最小长度（旧版「Base64 + 长度≥20」启发式阈值，仅用于过渡双读识别）。 */
    private static final int LEGACY_MIN_LENGTH = 20;

    /**
     * 密文标识前缀：加密值统一以该前缀开头，用于可靠判定是否已加密。
     * <p>替代旧版「Base64 + 长度≥20」启发式——明文密码若恰好是合法 Base64 且长度达标，
     * 会被误判为密文导致解密失败；显式前缀 + 鉴权标签天然规避此类歧义。</p>
     */
    public static final String ENCRYPTED_PREFIX = "dse:";

    private final EncryptionProperties encryptionProperties;

    public PasswordEncryptionUtil(EncryptionProperties encryptionProperties) {
        this.encryptionProperties = encryptionProperties;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        // 幂等守卫：已带密文前缀的值不再重复加密，防「假明文再加密」双重化
        if (isEncrypted(plaintext)) {
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
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        if (isEncrypted(ciphertext)) {
            return decryptPayload(ciphertext, ciphertext.substring(ENCRYPTED_PREFIX.length()), true);
        }
        // 过渡双读：无 dse: 前缀但命中旧启发式的存量密文，按旧格式（无前缀 Base64）尝试解密；
        // 解密失败说明是真正的历史明文（或密钥不同源），原样透传，不抛异常
        if (looksLikeLegacyCiphertext(ciphertext)) {
            return decryptPayload(ciphertext, ciphertext, false);
        }
        return ciphertext;
    }

    /**
     * Base64 密文载荷解密内部实现（新旧格式仅前缀差异，算法 / 密钥派生同源）。
     *
     * @param original 原始密文（透传 / 异常路径返回用）
     * @param payload  待 Base64 解码的载荷（新格式为去前缀后的部分，旧格式为原文）
     * @param strict   {@code true}=解码 / 验签失败抛异常（显式前缀密文须可解）；
     *                 {@code false}=失败按历史明文透传返回 original（过渡双读路径）
     */
    private String decryptPayload(String original, String payload, boolean strict) {
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length <= GCM_IV_LENGTH) {
                return original;
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
            if (!strict) {
                return original;
            }
            throw new IllegalStateException("密码解密失败", e);
        }
    }

    /**
     * 可靠判定是否密文：仅凭显式前缀标记（{@link #ENCRYPTED_PREFIX}），
     * 不再用「Base64 + 长度」启发式猜测，杜绝明文被误判为密文。
     */
    public boolean isEncrypted(String text) {
        return text != null && text.startsWith(ENCRYPTED_PREFIX);
    }

    /** 旧格式密文启发式识别（仅服务于 {@link #decrypt(String)} 的过渡双读，不对外暴露）。 */
    private static boolean looksLikeLegacyCiphertext(String text) {
        if (text.length() < LEGACY_MIN_LENGTH) {
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
            throw new IllegalStateException("未配置数据源加密密钥");
        }
        return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    }
}
