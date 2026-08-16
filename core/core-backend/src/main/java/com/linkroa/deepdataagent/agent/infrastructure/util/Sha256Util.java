package com.linkroa.deepdataagent.agent.infrastructure.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容 SHA-256 校验工具（技能包完整性校验）
 */
public final class Sha256Util {

    private Sha256Util() {
    }

    /**
     * 计算内容 SHA-256（64 位小写十六进制）
     */
    public static String hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}