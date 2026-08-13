package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.infrastructure.redis.port.DistributedLock;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.DistributedLockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis 分布式锁适配器。
 * <p>基于 {@code SET NX EX}（携带随机 token 与过期时间）实现互斥锁，
 * 释放时通过 Lua 比较 token 后删除，确保只有持有者可释放。
 * Redis 不可用时降级为无锁执行（返回空操作锁），业务不因锁组件故障而失败。</p>
 */
@Component
public class RedisDistributedLockAdapter implements DistributedLockPort {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockAdapter.class);

    /** 释放锁 Lua 脚本：仅当持有者 token 匹配时才删除 */
    private static final String UNLOCK_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> unlockScript;

    /**
     * 构造方法。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisDistributedLockAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.unlockScript = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
    }

    @Override
    public Optional<DistributedLock> tryLock(String key, Duration leaseTime) {
        try {
            String token = UUID.randomUUID().toString();
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, leaseTime);
            if (Boolean.TRUE.equals(acquired)) {
                return Optional.of(new RedisLock(redisTemplate, unlockScript, key, token));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis 分布式锁不可用，降级为无锁执行: {}", e.getMessage());
            return Optional.of(NoOpDistributedLock.INSTANCE);
        }
    }

    /**
     * Redis 锁句柄：持有者 token 匹配时删除键以释放锁。
     */
    private static final class RedisLock implements DistributedLock {

        private final StringRedisTemplate redisTemplate;
        private final DefaultRedisScript<Long> unlockScript;
        private final String key;
        private final String token;
        private volatile boolean released;

        RedisLock(StringRedisTemplate redisTemplate, DefaultRedisScript<Long> unlockScript,
                  String key, String token) {
            this.redisTemplate = redisTemplate;
            this.unlockScript = unlockScript;
            this.key = key;
            this.token = token;
        }

        @Override
        public void unlock() {
            if (released) {
                return;
            }
            released = true;
            try {
                redisTemplate.execute(unlockScript, List.of(key), token);
            } catch (Exception e) {
                log.warn("释放 Redis 分布式锁失败，将由过期时间兜底: {}", e.getMessage());
            }
        }
    }

    /**
     * Redis 不可用时的降级锁：不执行任何操作，保证业务无锁继续。
     */
    private enum NoOpDistributedLock implements DistributedLock {
        INSTANCE;

        @Override
        public void unlock() {
            // 无锁模式，无需任何操作
        }
    }
}
