package com.linkroa.deepdataagent.rag.domain.model;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChunkExtractCache} 归属值对象单测。
 * <p>覆盖新建态（主键为空、时间已填）、恢复态逐字段回填，以及归属三要素缺失
 * （知识库 / 分块 / 分类 / 缓存键空白）时的不变量拒绝。</p>
 *
 * @author DeepDataAgent
 */
class ChunkExtractCacheTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1001L;

    /** 测试分块ID */
    private static final Long CHUNK_ID = 501L;

    /** 测试缓存分类 */
    private static final CacheType CACHE_TYPE = CacheType.EXTRACT;

    /** 测试缓存键（32 位 MD5 hex 形态） */
    private static final String CACHE_KEY = "0123456789abcdef0123456789abcdef";

    /**
     * 场景：摄入期新建归属行。
     * 预期：主键为空由数据库回填，创建/更新时间均已填充。
     */
    @Test
    void should_createWithNullIdAndTimestamps_when_create_given_validArguments() {
        // when
        ChunkExtractCache attribution = ChunkExtractCache.create(KB_ID, CHUNK_ID, CACHE_TYPE, CACHE_KEY);

        // then
        assertNull(attribution.id(), "新建态主键必须为空，交由数据库自增回填");
        assertEquals(KB_ID, attribution.kbId());
        assertEquals(CHUNK_ID, attribution.chunkId());
        assertEquals(CACHE_TYPE, attribution.cacheType());
        assertEquals(CACHE_KEY, attribution.cacheKey());
        assertNotNull(attribution.createdAt());
        assertNotNull(attribution.updatedAt());
    }

    /**
     * 场景：从数据库恢复归属行。
     * 预期：逐字段原样回填（含主键与时间），不做任何改写。
     */
    @Test
    void should_keepEveryField_when_restore_given_persistedRow() {
        // given
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-09-02T10:00:00+08:00");

        // when
        ChunkExtractCache attribution = ChunkExtractCache.restore(9L, KB_ID, CHUNK_ID, CACHE_TYPE, CACHE_KEY,
                createdAt, updatedAt);

        // then
        assertEquals(9L, attribution.id());
        assertEquals(createdAt, attribution.createdAt());
        assertEquals(updatedAt, attribution.updatedAt());
    }

    /**
     * 场景：归属要素缺失（知识库 / 分块 / 分类为空，缓存键为 null 或空白）。
     * 预期：构造即拒绝，避免既无法幂等登记又无法定位缓存行的残缺归属落库。
     */
    @Test
    void should_rejectConstruction_when_constructor_given_missingAttributionElement() {
        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkExtractCache(null, null, CHUNK_ID, CACHE_TYPE, CACHE_KEY, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkExtractCache(null, KB_ID, null, CACHE_TYPE, CACHE_KEY, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkExtractCache(null, KB_ID, CHUNK_ID, null, CACHE_KEY, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkExtractCache(null, KB_ID, CHUNK_ID, CACHE_TYPE, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkExtractCache(null, KB_ID, CHUNK_ID, CACHE_TYPE, "   ", null, null));
    }
}
