package com.linkroa.deepdataagent.auth.infrastructure.util;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 密码散列工具（bcrypt，独立库，不引入完整 Spring Security）。
 * <p>bcrypt 对超过 72 字节的输入静默截断，故先对原始密码做 SHA-256 预哈希（固定 32 字节）
 * 再交给 bcrypt，消除截断熵损失；旧存量散列（bcrypt(明文)）在校验时仍兼容（先按明文验、再按预哈希验）。
 * 明文密码仅以 char[] 传入后立即丢弃。</p>
 */
public final class PasswordHashUtil {

    /** bcrypt 计算代价（越大越慢越安全）。 */
    private static final int COST = 12;

    /** 兜底散列：邮箱不存在时也执行一次 bcrypt 比对，抹平「存在 / 不存在」响应时延差（防账号枚举）。 */
    private static final String DUMMY_HASH =
            BCrypt.withDefaults().hashToString(COST, preHash("dummy-password-burn-time"));

    private PasswordHashUtil() {
    }

    /**
     * 生成 bcrypt 散列（输入先 SHA-256 预哈希）。
     */
    public static String hash(String rawPassword) {
        return BCrypt.withDefaults().hashToString(COST, preHash(rawPassword));
    }

    /**
     * 校验原始密码与散列是否匹配；兼容旧的 bcrypt(明文) 存量散列。
     */
    public static boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null) {
            return false;
        }
        // 先按新格式（预哈希）校验，再回退旧格式（明文直验），保持存量账号可登录
        if (BCrypt.verifyer().verify(preHash(rawPassword), passwordHash).verified) {
            return true;
        }
        return BCrypt.verifyer().verify(rawPassword.toCharArray(), passwordHash).verified;
    }

    /**
     * 兜底散列（避免分支时延差异）。
     */
    public static String dummyHash() {
        return DUMMY_HASH;
    }

    /**
     * SHA-256 预哈希：bcrypt 72 字节截断前先压缩到固定长度，保留全部密码熵。
     */
    private static char[] preHash(String rawPassword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest).toCharArray();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}