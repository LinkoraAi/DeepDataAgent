package com.linkroa.deepdataagent.auth.infrastructure.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * JWT 配置（{@code auth.jwt}）。
 * <p>HS256 签名密钥与有效期（天）；生产环境必须通过 {@code AUTH_JWT_SECRET} 配置，
 * 无默认值：漏配 / 未解析占位符时启动即失败（fail-fast），防止使用可预测密钥伪造 token。</p>
 */
@Component
@ConfigurationProperties(prefix = "auth.jwt")
public class JwtProperties {

    /** HS256 签名密钥（须 ≥ 32 字节）。 */
    private String secret;

    /** token 有效期（天）。 */
    private int expirationDays = 7;

    /** 启动期校验：密钥缺失 / 空白 / 未解析占位符 / 强度不足时启动即失败。 */
    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank() || secret.contains("${")) {
            throw new IllegalStateException(
                    "auth.jwt.secret 未配置：请通过环境变量 AUTH_JWT_SECRET 显式提供（未配置时启动即失败）");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("auth.jwt.secret 强度不足：HS256 签名密钥须 ≥ 32 字节");
        }
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public int getExpirationDays() {
        return expirationDays;
    }

    public void setExpirationDays(int expirationDays) {
        this.expirationDays = expirationDays;
    }
}