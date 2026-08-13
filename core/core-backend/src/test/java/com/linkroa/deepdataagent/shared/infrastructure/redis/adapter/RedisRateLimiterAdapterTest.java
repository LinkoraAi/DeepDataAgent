package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.config.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * RedisRateLimiterAdapter 单元测试。
 * <p>覆盖固定窗口计数放行/拒绝、脚本返回 null，以及 Redis 故障时降级放行的分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterAdapterTest {

    private static final String RATE_LIMIT_KEY = "dd:ratelimit:model-test:1";

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisRateLimiterAdapter adapter;

    @BeforeEach
    void setUp() {
        RedisProperties properties = new RedisProperties();
        properties.setRateLimitWindow(Duration.ofSeconds(5));
        properties.setRateLimitMax(1);
        adapter = new RedisRateLimiterAdapter(redisTemplate, properties);
    }

    @Test
    void should_returnTrue_when_tryAcquire_given_counterWithinLimit() {
        // given - Lua 脚本返回 1 表示窗口内未超限
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        // when
        boolean result = adapter.tryAcquire(RATE_LIMIT_KEY);

        // then
        assertTrue(result);
    }

    @Test
    void should_returnFalse_when_tryAcquire_given_counterExceedsLimit() {
        // given - Lua 脚本返回 0 表示窗口内已超限
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        // when
        boolean result = adapter.tryAcquire(RATE_LIMIT_KEY);

        // then
        assertFalse(result);
    }

    @Test
    void should_returnFalse_when_tryAcquire_given_scriptReturnsNull() {
        // given - 脚本异常返回 null 视为不通过
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        // when
        boolean result = adapter.tryAcquire(RATE_LIMIT_KEY);

        // then
        assertFalse(result);
    }

    @Test
    void should_returnTrue_when_tryAcquire_given_redisFailure() {
        // given - Redis 故障，降级放行（宽松模式）
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));

        // when
        boolean result = adapter.tryAcquire(RATE_LIMIT_KEY);

        // then
        assertTrue(result);
    }
}
