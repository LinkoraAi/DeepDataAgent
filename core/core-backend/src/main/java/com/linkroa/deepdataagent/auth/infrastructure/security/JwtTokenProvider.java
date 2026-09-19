package com.linkroa.deepdataagent.auth.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验（HS256，无状态）。
 * <p>token 携带 {@code jti}（唯一标识）供登出后撤销黑名单锚定；
 * 认证头解析支持 scheme 大小写不敏感（RFC 7235）。</p>
 */
@Component
public class JwtTokenProvider {

    /** 认证头 scheme 前缀（校验时大小写不敏感）。 */
    static final String BEARER_SCHEME = "Bearer ";

    private final SecretKey key;
    private final long expirationMillis;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = properties.getExpirationDays() * 24L * 3600L * 1000L;
    }

    /**
     * 签发 HS256 token，{@code sub} = 数字 user_id，附带唯一 {@code jti}。
     */
    public String generateToken(Long userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMillis))
                .signWith(key)
                .compact();
    }

    /**
     * 校验签名与有效期并返回 claim 载荷；非法 / 过期抛 {@link io.jsonwebtoken.JwtException}。
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 raw 认证头解析 bearer token；scheme 大小写不敏感，非法头返回 {@code null}。
     */
    public static String resolveBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String header = authorizationHeader.trim();
        if (!header.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
            return null;
        }
        String token = header.substring(BEARER_SCHEME.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 校验签名与有效期并解析数字 user_id；非法 / 过期抛 {@link io.jsonwebtoken.JwtException}。
     */
    public Long parseUserId(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    /**
     * token 有效期（秒），供登录响应下发。
     */
    public long expiresInSeconds() {
        return expirationMillis / 1000L;
    }
}