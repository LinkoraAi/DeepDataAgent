package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.config.RedisProperties;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.SchemaCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis Schema 缓存适配器。
 * <p>将 Schema 描述文本缓存到 Redis（key 格式 {@code dd:schema:{datasourceId}}），
 * 有效期默认 60 秒可配置。Redis 不可用时各操作均降级：读取视为未命中（miss）、
 * 写入与失效静默跳过，保证主流程不因缓存故障失败。</p>
 */
@Component
public class RedisSchemaCacheAdapter implements SchemaCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisSchemaCacheAdapter.class);

    /** 缓存键前缀 */
    private static final String KEY_PREFIX = "dd:schema:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    /**
     * 构造方法。
     *
     * @param redisTemplate Redis 字符串模板
     * @param properties    Redis 能力配置
     */
    public RedisSchemaCacheAdapter(StringRedisTemplate redisTemplate, RedisProperties properties) {
        this.redisTemplate = redisTemplate;
        this.ttl = properties.getSchemaCacheTtl();
    }

    @Override
    public Optional<String> get(Long datasourceId) {
        try {
            String value = redisTemplate.opsForValue().get(key(datasourceId));
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("Redis 读取 Schema 缓存失败，降级为未命中: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(Long datasourceId, String schema) {
        try {
            redisTemplate.opsForValue().set(key(datasourceId), schema, ttl);
        } catch (Exception e) {
            log.warn("Redis 写入 Schema 缓存失败，跳过: {}", e.getMessage());
        }
    }

    @Override
    public void evict(Long datasourceId) {
        try {
            redisTemplate.delete(key(datasourceId));
        } catch (Exception e) {
            log.warn("Redis 失效 Schema 缓存失败，跳过: {}", e.getMessage());
        }
    }

    /**
     * 组装缓存键。
     *
     * @param datasourceId 数据源 ID
     * @return Redis 键
     */
    private String key(Long datasourceId) {
        return KEY_PREFIX + datasourceId;
    }
}
