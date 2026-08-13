package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.config.RedisProperties;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.RateLimiterPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 限流适配器。
 * <p>基于 Lua 脚本实现固定窗口计数：窗口内首次请求设置过期时间，计数不超过上限即放行。
 * Redis 不可用时降级为放行（宽松模式），业务不因限流组件故障而失败。</p>
 */
@Component
public class RedisRateLimiterAdapter implements RateLimiterPort {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterAdapter.class);

    /** 固定窗口 Lua 脚本：KEYS[1]=限流键，ARGV[1]=窗口毫秒数，ARGV[2]=上限 */
    private static final String FIXED_WINDOW_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if current <= tonumber(ARGV[2]) then
                return 1
            end
            return 0
            """;

    private final StringRedisTemplate redisTemplate;
    private final long windowMillis;
    private final int maxRequests;
    private final DefaultRedisScript<Long> script;

    /**
     * 构造方法。
     *
     * @param redisTemplate Redis 字符串模板
     * @param properties    Redis 能力配置
     */
    public RedisRateLimiterAdapter(StringRedisTemplate redisTemplate, RedisProperties properties) {
        this.redisTemplate = redisTemplate;
        this.windowMillis = properties.getRateLimitWindow().toMillis();
        this.maxRequests = properties.getRateLimitMax();
        this.script = new DefaultRedisScript<>(FIXED_WINDOW_SCRIPT, Long.class);
    }

    @Override
    public boolean tryAcquire(String key) {
        try {
            Long result = redisTemplate.execute(script, List.of(key),
                    String.valueOf(windowMillis), String.valueOf(maxRequests));
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            log.warn("Redis 限流不可用，降级放行: {}", e.getMessage());
            return true;
        }
    }
}
