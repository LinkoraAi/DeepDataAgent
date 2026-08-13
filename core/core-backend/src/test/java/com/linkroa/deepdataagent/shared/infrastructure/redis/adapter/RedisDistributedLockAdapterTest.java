package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.infrastructure.redis.port.DistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RedisDistributedLockAdapter 单元测试。
 * <p>覆盖锁获取成功/失败、释放时 token 校验（Lua 脚本执行）、重复释放幂等，
 * 以及 Redis 故障时降级为无锁执行的语义。</p>
 */
@ExtendWith(MockitoExtension.class)
class RedisDistributedLockAdapterTest {

    private static final String LOCK_KEY = "dd:lock:default-model";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisDistributedLockAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisDistributedLockAdapter(redisTemplate);
    }

    @Test
    void should_returnLock_when_tryLock_given_lockAcquired() {
        // given - SET NX EX 成功
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);

        // when
        Optional<DistributedLock> lockOpt = adapter.tryLock(LOCK_KEY, Duration.ofSeconds(10));

        // then
        assertTrue(lockOpt.isPresent());
        verify(valueOperations).setIfAbsent(eq(LOCK_KEY), anyString(), eq(Duration.ofSeconds(10)));
    }

    @Test
    void should_returnEmpty_when_tryLock_given_lockNotAcquired() {
        // given - 锁已被其他持有者占用
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(false);

        // when
        Optional<DistributedLock> lockOpt = adapter.tryLock(LOCK_KEY, Duration.ofSeconds(10));

        // then
        assertTrue(lockOpt.isEmpty());
    }

    @Test
    void should_releaseLock_when_unlock_given_lockHeld() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        DistributedLock lock = adapter.tryLock(LOCK_KEY, Duration.ofSeconds(10)).orElseThrow();

        // when
        lock.unlock();

        // then - 通过 Lua 脚本携带 token 删除锁键
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void should_executeUnlockOnce_when_unlockTwice_given_lockHeld() {
        // given - 释放必须幂等
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        DistributedLock lock = adapter.tryLock(LOCK_KEY, Duration.ofSeconds(10)).orElseThrow();

        // when
        lock.unlock();
        lock.unlock();

        // then
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void should_notThrow_when_unlock_given_redisFailure() {
        // given - 释放失败由过期时间兜底
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        doThrow(new RuntimeException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
        DistributedLock lock = adapter.tryLock(LOCK_KEY, Duration.ofSeconds(10)).orElseThrow();

        // when & then
        assertDoesNotThrow(lock::unlock);
    }

    @Test
    void should_returnNoOpLock_when_tryLock_given_redisFailure() {
        // given - Redis 故障，降级为无锁执行（返回空操作锁）
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        // when
        Optional<DistributedLock> lockOpt = adapter.tryLock(LOCK_KEY, Duration.ofSeconds(10));

        // then
        assertTrue(lockOpt.isPresent());
        assertDoesNotThrow(lockOpt.get()::unlock);
    }
}
