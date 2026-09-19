package com.linkroa.deepdataagent.auth.application.validation;

import org.apache.commons.lang3.StringUtils;

/**
 * 认证应用级校验：原始密码强度（原始密码仅在内存中比对 / 散列，不入领域模型）。
 */
public final class AuthValidator {

    /** 密码最小长度 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private AuthValidator() {
    }

    /**
     * 校验原始密码强度（非法抛 {@link IllegalArgumentException}）。
     */
    public static void validatePassword(String rawPassword) {
        if (StringUtils.isBlank(rawPassword)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
    }
}