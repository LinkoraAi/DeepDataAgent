package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.config.RedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RedisSchemaCacheAdapter 单元测试。
 * <p>覆盖缓存读取命中/未命中、写入与失效交互，以及 Redis 故障时的降级语义
 * （读视为 miss、写与失效静默跳过）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RedisSchemaCacheAdapterTest {

    private static final Long DATASOURCE_ID = 1L;
    private static final String CACHE_KEY = "dd:schema:1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisSchemaCacheAdapter adapter;

    @BeforeEach
    void setUp() {
        RedisProperties properties = new RedisProperties();
        properties.setSchemaCacheTtl(Duration.ofSeconds(60));
        adapter = new RedisSchemaCacheAdapter(redisTemplate, properties);
    }

    // ==================== get ====================

    @Test
    void should_returnCachedValue_when_get_given_cacheHit() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn("schema-text");

        // when
        Optional<String> result = adapter.get(DATASOURCE_ID);

        // then
        assertTrue(result.isPresent());
        assertEquals("schema-text", result.get());
        verify(valueOperations).get(CACHE_KEY);
    }

    @Test
    void should_returnEmpty_when_get_given_cacheMiss() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);

        // when
        Optional<String> result = adapter.get(DATASOURCE_ID);

        // then
        assertTrue(result.isEmpty());
    }

    @Test
    void should_returnEmpty_when_get_given_redisFailure() {
        // given - Redis 故障，读取降级为未命中
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenThrow(new RuntimeException("redis down"));

        // when
        Optional<String> result = adapter.get(DATASOURCE_ID);

        // then
        assertTrue(result.isEmpty());
    }

    // ==================== put ====================

    @Test
    void should_setValueWithTtl_when_put_given_schemaText() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // when
        adapter.put(DATASOURCE_ID, "schema-text");

        // then - 携带默认 60s TTL 写入
        verify(valueOperations).set(CACHE_KEY, "schema-text", Duration.ofSeconds(60));
    }

    @Test
    void should_notThrow_when_put_given_redisFailure() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("redis down")).when(valueOperations)
                .set(eq(CACHE_KEY), anyString(), any(Duration.class));

        // when & then - 写入失败静默跳过，不向上抛
        assertDoesNotThrow(() -> adapter.put(DATASOURCE_ID, "schema-text"));
    }

    // ==================== evict ====================

    @Test
    void should_deleteKey_when_evict_given_datasourceId() {
        // given
        when(redisTemplate.delete(CACHE_KEY)).thenReturn(true);

        // when
        adapter.evict(DATASOURCE_ID);

        // then
        verify(redisTemplate).delete(CACHE_KEY);
    }

    @Test
    void should_notThrow_when_evict_given_redisFailure() {
        // given
        when(redisTemplate.delete(CACHE_KEY)).thenThrow(new RuntimeException("redis down"));

        // when & then - 失效失败静默跳过
        assertDoesNotThrow(() -> adapter.evict(DATASOURCE_ID));
    }
}
