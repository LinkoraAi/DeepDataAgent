package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.infrastructure.redis.port.DistributedLock;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.DistributedLockPort;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存分布式锁实现（供单元测试使用）。
 * <p>与 {@link RedisDistributedLockAdapter} 语义一致：键级互斥、持有到期自动释放、
 * 仅持有者可释放。线程安全基于 {@link ConcurrentHashMap} 原子操作。</p>
 */
public class InMemoryDistributedLockAdapter implements DistributedLockPort {

    /** 锁条目：记录过期纳秒时间戳与持有 token */
    private record LockEntry(long expiryNanos, String token) {
    }

    private final ConcurrentMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<DistributedLock> tryLock(String key, Duration leaseTime) {
        long now = System.nanoTime();
        long expiryNanos = now + leaseTime.toNanos();
        String token = java.util.UUID.randomUUID().toString();
        LockEntry entry = new LockEntry(expiryNanos, token);

        LockEntry existing = locks.putIfAbsent(key, entry);
        if (existing == null) {
            return Optional.of(new InMemoryLock(key, token));
        }
        // 旧锁已过期则可抢占（CAS 确保并发安全）
        if (existing.expiryNanos() <= now && locks.replace(key, existing, entry)) {
            return Optional.of(new InMemoryLock(key, token));
        }
        return Optional.empty();
    }

    /**
     * 内存锁句柄：仅当 token 匹配时移除键以释放锁。
     */
    private final class InMemoryLock implements DistributedLock {

        private final String key;
        private final String token;
        private volatile boolean released;

        InMemoryLock(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void unlock() {
            if (released) {
                return;
            }
            released = true;
            LockEntry current = locks.get(key);
            if (current != null && current.token().equals(token)) {
                locks.remove(key, current);
            }
        }
    }
}
