package com.linkroa.deepdataagent.runtime.application.service.assembly;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装配结果进程内 LRU 缓存（slim-agent-assembly D3：满 128 逐出最久未访问单条，取代「全清」）。
 * <p>基于 JDK {@link LinkedHashMap}（{@code accessOrder=true} + {@code removeEldestEntry}）实现，
 * 不引入 Caffeine / Guava。容量到达上限时<b>仅淘汰最久未访问的一条</b>，消除整体清空导致的重建风暴。</p>
 * <p><b>自带互斥</b>：{@code get} / {@code put} / {@code size} 均为 {@code synchronized} 方法，
 * {@link LinkedHashMap} 非线程安全、且 accessOrder 下读操作会改写结构，故 MUST NOT 依赖调用方同步；
 * 本类内部锁保证并发读写不损坏结构。</p>
 * <p><b>TTL 语义保留</b>：条目随值记录加载时刻，{@link #get(Object, long)} 在读取时按条目时间戳判定
 * 过期——过期条目就地移除并返回 {@code null}（视作未命中，触发上层重解析）。</p>
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型（装配快照，不含明文凭据）
 */
final class AssemblyLruCache<K, V> {

    /** 最大条数（到达上限时逐出最久未访问单条）。 */
    private final int maxSize;
    /** TTL 毫秒数（get 时按条目加载时刻判定过期）。 */
    private final long ttlMillis;
    /** 底层 LRU map（accessOrder=true，重写 removeEldestEntry 实现按容量逐出）。 */
    private final LinkedHashMap<K, Timestamped<V>> map;

    AssemblyLruCache(int maxSize, long ttlMillis) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Timestamped<V>> eldest) {
                return size() > AssemblyLruCache.this.maxSize;
            }
        };
    }

    /**
     * 读取缓存值：命中且未过期返回值（并刷新访问序），未命中或已过期返回 {@code null}（过期条目就地移除）。
     *
     * @param key 缓存键
     * @param now 当前毫秒时间戳
     * @return 命中且新鲜的值；否则 {@code null}
     */
    synchronized V get(K key, long now) {
        Timestamped<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (now - entry.loadedAt() >= ttlMillis) {
            map.remove(key);
            return null;
        }
        return entry.value();
    }

    /**
     * 写入缓存值（记录加载时刻）；超出容量时由 {@code removeEldestEntry} 逐出最久未访问单条。
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param now   当前毫秒时间戳
     */
    synchronized void put(K key, V value, long now) {
        map.put(key, new Timestamped<>(value, now));
    }

    /** 当前条目数（含尚未按 TTL 惰性清除的过期项，仅用于观察与测试）。 */
    synchronized int size() {
        return map.size();
    }

    /** 逐出并清空全部条目（仅测试用于重置隔离态，运行期不调用）。 */
    synchronized void clear() {
        map.clear();
    }

    /** 带加载时刻的缓存条目（TTL 判定依据）。 */
    private record Timestamped<V>(V value, long loadedAt) {
    }
}
