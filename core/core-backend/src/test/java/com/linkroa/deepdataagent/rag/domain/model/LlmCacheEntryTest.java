package com.linkroa.deepdataagent.rag.domain.model;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmCacheEntry} 单元测试（域模型 record：紧凑构造器不变量校验 + create/restore 工厂）。
 */
class LlmCacheEntryTest {

    /** 合法缓存键：32 位 MD5 hex */
    private static final String CACHE_KEY = "0123456789abcdef0123456789abcdef";

    /**
     * 场景：以合法入参通过 {@code create} 工厂新建条目。
     * 预期：主键为 null、字段逐一穿透、cacheType 为 ANSWER、创建/更新时间已填充且时区为东八区。
     */
    @Test
    void should_createEntryWithAllFields_when_create_given_validInput() {
        // given
        Long kbId = 1001L;
        String model = "gpt-4o-mini";
        String prompt = "system+user";
        String response = "answer";
        Integer totalTokens = 42;

        // when
        LlmCacheEntry entry = LlmCacheEntry.create(kbId, CACHE_KEY, CacheType.ANSWER, model, prompt, response, totalTokens);

        // then
        assertNull(entry.id(), "新建条目主键应为 null");
        assertEquals(kbId, entry.kbId());
        assertEquals(CACHE_KEY, entry.cacheKey());
        assertEquals(CacheType.ANSWER, entry.cacheType(), "通用 LLM 调用默认归入 ANSWER 分类");
        assertEquals(model, entry.model());
        assertEquals(prompt, entry.prompt());
        assertEquals(response, entry.response());
        assertEquals(totalTokens, entry.totalTokens());
        assertNotNull(entry.createdAt(), "创建时间应被填充");
        assertNotNull(entry.updatedAt(), "更新时间应被填充");
        assertEquals(8, entry.createdAt().getOffset().getTotalSeconds() / 3600, "时间戳应为东八区 (Asia/Shanghai)");
    }

    /**
     * 场景：以不同 {@link CacheType}（问题改写/关键词提取/实体描述）新建条目。
     * 预期：cacheType 分类正确穿透，不强制 ANSWER。
     */
    @Test
    void should_persistCacheType_when_create_given_nonAnswerCategory() {
        // given
        Long kbId = 2002L;

        // when
        LlmCacheEntry rewrite = LlmCacheEntry.create(kbId, CACHE_KEY, CacheType.QUERY_REWRITE, "m", "p", "r", null);
        LlmCacheEntry extract = LlmCacheEntry.create(kbId, CACHE_KEY, CacheType.KEYWORD_EXTRACT, "m", "p", "r", null);
        LlmCacheEntry desc = LlmCacheEntry.create(kbId, CACHE_KEY, CacheType.ENTITY_DESC, "m", "p", "r", null);

        // then
        assertEquals(CacheType.QUERY_REWRITE, rewrite.cacheType());
        assertEquals(CacheType.KEYWORD_EXTRACT, extract.cacheType());
        assertEquals(CacheType.ENTITY_DESC, desc.cacheType());
        assertNull(rewrite.totalTokens(), "totalTokens 允许为 null");
    }

    /**
     * 场景：已落库行通过 {@code restore} 工厂恢复（携带主键与审计时间）。
     * 预期：全字段还原，createdAt/updatedAt 原样保留。
     */
    @Test
    void should_restoreAllFields_when_restore_given_fullRow() {
        // given
        Long id = 99L;
        Long kbId = 3003L;
        OffsetDateTime createdAt = OffsetDateTime.now();
        OffsetDateTime updatedAt = createdAt.plusMinutes(5);

        // when
        LlmCacheEntry entry = LlmCacheEntry.restore(id, kbId, CACHE_KEY, CacheType.ANSWER, "m", "p", "r", 7,
                createdAt, updatedAt);

        // then
        assertEquals(id, entry.id());
        assertEquals(kbId, entry.kbId());
        assertEquals(CacheType.ANSWER, entry.cacheType());
        assertEquals(7, entry.totalTokens());
        assertEquals(createdAt, entry.createdAt());
        assertEquals(updatedAt, entry.updatedAt());
    }

    /**
     * 场景：cacheType 为 null 时新建条目（紧凑构造器不变量校验）。
     * 预期：抛出 {@link IllegalArgumentException} 并提示缓存分类。
     */
    @Test
    void should_throwException_when_create_given_nullCacheType() {
        // given / when / then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> LlmCacheEntry.create(1L, CACHE_KEY, null, "m", "p", "r", 1));
        assertTrue(exception.getMessage().contains("缓存分类"));
    }

    /**
     * 场景：kbId 为 null 时新建条目。
     * 预期：抛出 {@link IllegalArgumentException} 并提示知识库。
     */
    @Test
    void should_throwException_when_create_given_nullKbId() {
        // given / when / then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> LlmCacheEntry.create(null, CACHE_KEY, CacheType.ANSWER, "m", "p", "r", 1));
        assertTrue(exception.getMessage().contains("知识库"));
    }

    /**
     * 场景：cacheKey 为空白/超长/过短（不满足 32 位 MD5 hex 契约）。
     * 预期：分别抛出 {@link IllegalArgumentException}。
     */
    @Test
    void should_throwException_when_create_given_invalidCacheKey() {
        // given / when / then
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> LlmCacheEntry.create(1L, "   ", CacheType.ANSWER, "m", "p", "r", 1));
        assertTrue(blank.getMessage().contains("缓存键"));

        assertThrows(IllegalArgumentException.class,
                () -> LlmCacheEntry.create(1L, "short", CacheType.ANSWER, "m", "p", "r", 1));

        assertThrows(IllegalArgumentException.class,
                () -> LlmCacheEntry.create(1L, CACHE_KEY + "0", CacheType.ANSWER, "m", "p", "r", 1),
                "超过 32 位应拒绝");
    }

    /**
     * 场景：以 {@code restore} 直接构造非法条目（绕过 create，验证紧凑构造器同样生效）。
     * 预期：cacheType 缺失时抛出 {@link IllegalArgumentException}。
     */
    @Test
    void should_throwException_when_restore_given_missingCacheType() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> LlmCacheEntry.restore(null, 1L, CACHE_KEY, null, "m", "p", "r", 1, null, null));
    }
}