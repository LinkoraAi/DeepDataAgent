package com.linkroa.deepdataagent.shared.infrastructure.redis.adapter;

import com.linkroa.deepdataagent.shared.infrastructure.redis.port.SchemaCachePort;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存 Schema 缓存实现（供单元测试使用）。
 * <p>与 {@link RedisSchemaCacheAdapter} 语义一致：带 TTL 的进程内缓存，
 * 命中未过期条目返回缓存值，否则视为未命中。</p>
 */
public class InMemorySchemaCacheAdapter implements SchemaCachePort {

    /** 缓存条目 */
    private record CacheEntry(String schema, long cachedAtNanos) {
    }

    private final Duration ttl;
    private final ConcurrentMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 构造方法。
     *
     * @param ttl 缓存有效期
     */
    public InMemorySchemaCacheAdapter(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public Optional<String> get(Long datasourceId) {
        CacheEntry entry = cache.get(datasourceId);
        if (entry == null || System.nanoTime() - entry.cachedAtNanos() >= ttl.toNanos()) {
            return Optional.empty();
        }
        return Optional.of(entry.schema());
    }

    @Override
    public void put(Long datasourceId, String schema) {
        cache.put(datasourceId, new CacheEntry(schema, System.nanoTime()));
    }

    @Override
    public void evict(Long datasourceId) {
        cache.remove(datasourceId);
    }
}
