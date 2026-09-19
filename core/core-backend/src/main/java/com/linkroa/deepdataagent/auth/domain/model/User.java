package com.linkroa.deepdataagent.auth.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 用户领域聚合（对应 users 表）。
 * <p>用户以邮箱为登录唯一标识，密码仅以 bcrypt 散列存储；
 * 明文密码 / 散列值均不得出现在任何接口响应与日志中。</p>
 *
 * @param userId       数字用户 ID（数据库自增主键，即 JWT {@code sub}）
 * @param email        登录邮箱（唯一）
 * @param passwordHash bcrypt 密码散列
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record User(
        Long userId,
        String email,
        String passwordHash,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * 紧凑构造器：领域不变量校验（邮箱格式、散列非空）；邮箱统一小写归一化，
     * 保证 {@code John@x.com} 与 {@code john@x.com} 视为同一账号（结合唯一索引防重复注册）。
     */
    public User {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
        if (StringUtils.isBlank(email)) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("邮箱格式非法");
        }
        if (StringUtils.isBlank(passwordHash)) {
            throw new IllegalArgumentException("密码散列不能为空");
        }
    }

    /**
     * 创建新用户（注册场景）。
     */
    public static User register(Long userId, String email, String passwordHash) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new User(userId, email, passwordHash, now, now);
    }

    /**
     * 从数据库恢复（查询场景）。
     */
    public static User restore(
            Long userId,
            String email,
            String passwordHash,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        return new User(userId, email, passwordHash, createdAt, updatedAt);
    }
}