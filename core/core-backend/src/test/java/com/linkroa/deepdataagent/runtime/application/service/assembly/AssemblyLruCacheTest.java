package com.linkroa.deepdataagent.runtime.application.service.assembly;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssemblyLruCache} 单测（slim-agent-assembly D3：LRU 逐出 + TTL + 自带互斥）。
 * <p>验证满容量逐出的是<b>最久未访问单条</b>（非全清）、读操作刷新访问序、TTL 到期就地失效、
 * 并发读写不损坏结构。</p>
 */
class AssemblyLruCacheTest {

    private static final long TTL = 60_000L;

    @Test
    void should_evictLeastRecentlyUsed_when_put_given_capacityExceeded() {
        // given（容量 2 的 LRU）
        AssemblyLruCache<String, String> cache = new AssemblyLruCache<>(2, TTL);

        // when（依次写入 k1、k2、k3）
        cache.put("k1", "v1", 0L);
        cache.put("k2", "v2", 0L);
        cache.put("k3", "v3", 0L);

        // then（最久未访问的 k1 被逐出，k2、k3 保留，容量不超 2）
        assertNull(cache.get("k1", 0L));
        assertEquals("v2", cache.get("k2", 0L));
        assertEquals("v3", cache.get("k3", 0L));
        assertEquals(2, cache.size());
    }

    @Test
    void should_keepRecentlyAccessed_when_put_given_accessOrderRefreshedByGet() {
        // given（容量 2：先写 k1、k2）
        AssemblyLruCache<String, String> cache = new AssemblyLruCache<>(2, TTL);
        cache.put("k1", "v1", 0L);
        cache.put("k2", "v2", 0L);

        // when（读 k1 刷新其访问序 → k2 变为最久未访问；再写 k3 触发逐出）
        cache.get("k1", 0L);
        cache.put("k3", "v3", 0L);

        // then（被逐出的是 k2 而非 k1，证明按访问序而非插入序逐出）
        assertNull(cache.get("k2", 0L));
        assertEquals("v1", cache.get("k1", 0L));
        assertEquals("v3", cache.get("k3", 0L));
    }

    @Test
    void should_returnNullAndRemove_when_get_given_entryExpiredByTtl() {
        // given（写入 k1 于 t=0）
        AssemblyLruCache<String, String> cache = new AssemblyLruCache<>(8, TTL);
        cache.put("k1", "v1", 0L);

        // when（TTL 边界内命中、达到 TTL 判定过期）
        assertEquals("v1", cache.get("k1", TTL - 1));
        assertNull(cache.get("k1", TTL));

        // then（过期条目就地移除）
        assertEquals(0, cache.size());
    }

    @Test
    void should_notCorrupt_when_concurrentGetAndPut_given_sharedCache() throws InterruptedException {
        // given（容量远小于写入量的缓存 + 多线程并发读写）
        int maxSize = 64;
        AssemblyLruCache<Integer, Integer> cache = new AssemblyLruCache<>(maxSize, TTL);
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        try {
            // when（各线程写入连续键并回读，触发大量访问序改写与逐出）
            List<Runnable> tasks = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                tasks.add(() -> {
                    awaitQuietly(start);
                    for (int i = base; i < base + perThread; i++) {
                        cache.put(i, i, System.currentTimeMillis());
                        if (cache.get(i, System.currentTimeMillis()) != null) {
                            reads.incrementAndGet();
                        }
                    }
                });
            }
            tasks.forEach(pool::submit);
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            // then（结构未损坏：容量恒定不超过上限，且并发读命中总数合理 > 0）
            assertTrue(cache.size() <= maxSize, "逐出后容量应始终不超过 maxSize，实际=" + cache.size());
            assertTrue(reads.get() > 0, "并发写入后应能读到部分未逐出条目");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_storeAndRetrieve_when_put_then_get_given_freshEntry() {
        // given
        AssemblyLruCache<String, String> cache = new AssemblyLruCache<>(4, TTL);

        // when
        cache.put("only", "value", 1000L);

        // then
        assertNotNull(cache.get("only", 1000L));
        assertEquals("value", cache.get("only", 1001L));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
