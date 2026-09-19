package com.linkroa.deepdataagent.auth.infrastructure.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 认证接口简单固定窗口限流器（进程内，单机部署够用）。
 * <p>以 {@code key}（如 IP / 邮箱）为维度统计 1 分钟窗口内请求数，超过 {@code limit} 拒绝；
 * 窗口过期自动重置，超阈值（1 万）时顺带清理过期桶，避免内存持续增长。</p>
 */
@Component
public class AuthRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final int MAX_BUCKETS_BEFORE_SWEEP = 10_000;

    /** key → 窗口起始毫秒 + 请求计数。 */
    private static final class Bucket {

        private final long windowStartMillis;
        private final AtomicLong count = new AtomicLong(0);

        private Bucket(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试获取一次配额；窗口内累计超过 {@code limit} 返回 {@code false}。
     */
    public boolean tryAcquire(String key, int limit) {
        long now = System.currentTimeMillis();
        if (buckets.size() > MAX_BUCKETS_BEFORE_SWEEP) {
            buckets.entrySet().removeIf(entry ->
                    now - entry.getValue().windowStartMillis >= WINDOW.toMillis());
        }
        Bucket bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMillis >= WINDOW.toMillis()) {
                return new Bucket(now);
            }
            return existing;
        });
        return bucket.count.incrementAndGet() <= limit;
    }
}