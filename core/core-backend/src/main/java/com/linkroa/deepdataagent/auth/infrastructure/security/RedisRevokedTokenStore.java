package com.linkroa.deepdataagent.auth.infrastructure.security;

import com.linkroa.deepdataagent.auth.application.port.RevokedTokenStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的已撤销 JWT 存储：{@code auth:token:revoked:{jti} → 1}，TTL = 原 token 剩余有效期。
 */
@Component
public class RedisRevokedTokenStore implements RevokedTokenStore {

    private static final String KEY_PREFIX = "auth:token:revoked:";

    private final StringRedisTemplate redisTemplate;

    public RedisRevokedTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isRevoked(String jti) {
        return jti != null && Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
    }

    @Override
    public void revoke(String jti, long ttlSeconds) {
        if (jti == null) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
    }
}