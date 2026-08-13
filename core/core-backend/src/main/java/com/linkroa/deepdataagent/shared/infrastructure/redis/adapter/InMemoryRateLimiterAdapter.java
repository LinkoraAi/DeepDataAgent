package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.infrastructure.redis.port.RateLimiterPort;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存限流实现（供单元测试使用）。
 * <p>与 {@link RedisRateLimiterAdapter} 语义一致：固定窗口内计数，
 * 超过上限即拒绝；窗口到期后重新计数。</p>
 */
public class InMemoryRateLimiterAdapter implements RateLimiterPort {

    /** 窗口状态 */
    private record WindowState(long windowStartNanos, int count) {
    }

    private final Duration window;
    private final int maxRequests;
    private final ConcurrentMap<String, WindowState> states = new ConcurrentHashMap<>();

    /**
     * 构造方法。
     *
     * @param window      限流窗口
     * @param maxRequests 窗口内允许的最大请求次数
     */
    public InMemoryRateLimiterAdapter(Duration window, int maxRequests) {
        this.window = window;
        this.maxRequests = maxRequests;
    }

    @Override
    public boolean tryAcquire(String key) {
        long now = System.nanoTime();
        WindowState state = states.compute(key, (k, s) -> {
            if (s == null || now - s.windowStartNanos() >= window.toNanos()) {
                return new WindowState(now, 1);
            }
            return new WindowState(s.windowStartNanos(), s.count() + 1);
        });
        return state.count() <= maxRequests;
    }
}
