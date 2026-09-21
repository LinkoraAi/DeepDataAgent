package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditFieldUtils;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.ChunkExtractCacheEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.ChunkExtractCacheMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcChunkExtractCacheRepository} 仓储实现单测（mock MyBatis Mapper）。
 * <p>覆盖登记幂等（同批重复键在 Java 侧去重、全部冲突时零新增且不报错）、批量分批、
 * 归属多对多分组（同键多分块 / 同分块多键）、引用判定只返回仍有引用的键、按分块删除归属行，
 * 以及各方法的空参短路（不下发 SQL）。注解 SQL 文本语义（{@code ON CONFLICT DO NOTHING}）
 * 按项目惯例不在单测校验。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class JdbcChunkExtractCacheRepositoryTest {

    /** 测试知识库ID */
    private static final Long KB_ID = 1001L;

    /** 测试分块ID（待删文档的分块） */
    private static final Long CHUNK_ID = 501L;

    /** 另一分块ID（其他存活文档的分块，用于共用键场景） */
    private static final Long OTHER_CHUNK_ID = 777L;

    /** 抽取缓存分类（摄入期抽取与媒体描述所属分区） */
    private static final CacheType CACHE_TYPE = CacheType.EXTRACT;

    /** 归属登记单批上限（与被测实现内部分批口径一致） */
    private static final int REGISTER_BATCH_SIZE = 500;

    /** 缓存键样例（32 位 MD5 hex 形态） */
    private static final String KEY_1 = "0123456789abcdef0123456789abcdef";
    private static final String KEY_2 = "1123456789abcdef0123456789abcdef";
    private static final String KEY_3 = "2123456789abcdef0123456789abcdef";

    /** 归属 Mapper Mock */
    @Mock
    private ChunkExtractCacheMapper mapper;

    /** 被测仓储 */
    @InjectMocks
    private JdbcChunkExtractCacheRepository repository;

    private static ChunkExtractCacheEntity rowOf(Long chunkId, String cacheKey) {
        ChunkExtractCacheEntity entity = new ChunkExtractCacheEntity();
        entity.setId(1L);
        entity.setKbId(KB_ID);
        entity.setChunkId(chunkId);
        entity.setCacheType(CACHE_TYPE.name());
        entity.setCacheKey(cacheKey);
        entity.setCreatedAt(OffsetDateTime.parse("2026-09-01T10:00:00+08:00"));
        entity.setUpdatedAt(OffsetDateTime.parse("2026-09-01T10:00:00+08:00"));
        return entity;
    }

    /**
     * 生成互不相同的 32 位十六进制缓存键。
     *
     * @param count 生成个数
     * @return 键列表
     */
    private static List<String> keysOf(int count) {
        return IntStream.range(0, count).mapToObj(i -> String.format("%032x", i)).toList();
    }

    /**
     * 场景：同批登记入参含重复键与空白键（重解析 / 重试导致的重复归属输入）。
     * 预期：空白键被丢弃、重复键只保留首个出现顺序，单批下发一次，主键置空且审计字段补齐。
     */
    @Test
    void should_registerDistinctKeysInOrder_when_registerAll_given_duplicateAndBlankKeys() {
        // given
        when(mapper.insertBatchIfAbsent(anyList())).thenReturn(2);

        // when
        int inserted = repository.registerAll(KB_ID, CHUNK_ID, CACHE_TYPE,
                Arrays.asList(KEY_1, KEY_2, KEY_1, "   ", null, ""));

        // then
        assertEquals(2, inserted, "新增行数取数据库实际插入数");
        ArgumentCaptor<List<ChunkExtractCacheEntity>> captor = ArgumentCaptor.captor();
        verify(mapper, times(1)).insertBatchIfAbsent(captor.capture());
        List<ChunkExtractCacheEntity> rows = captor.getValue();
        assertEquals(List.of(KEY_1, KEY_2), rows.stream().map(ChunkExtractCacheEntity::getCacheKey).toList(),
                "重复键必须去重、空白键必须丢弃且保持登记顺序");
        for (ChunkExtractCacheEntity row : rows) {
            assertNull(row.getId(), "插入前主键必须置空由数据库自增");
            assertEquals(KB_ID, row.getKbId());
            assertEquals(CHUNK_ID, row.getChunkId());
            assertEquals(CACHE_TYPE.name(), row.getCacheType(), "分类以枚举名落库，与 llm_cache.cache_type 同口径");
            assertNotNull(row.getCreatedAt());
            assertNotNull(row.getUpdatedAt());
            assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, row.getCreatedBy());
            assertEquals(RagAuditFieldUtils.DEFAULT_OPERATOR, row.getUpdatedBy());
        }
    }

    /**
     * 场景：同一分块对同一批缓存键重复登记（数据库唯一键冲突分支）。
     * 预期：零新增行数、静默成功（幂等），且只下发一次批量插入、不触碰其它交互。
     */
    @Test
    void should_returnZeroWithoutError_when_registerAll_given_keysAlreadyAttributed() {
        // given：全部行命中 ON CONFLICT DO NOTHING
        when(mapper.insertBatchIfAbsent(anyList())).thenReturn(0);

        // when
        int inserted = assertDoesNotThrow(() -> repository.registerAll(KB_ID, CHUNK_ID, CACHE_TYPE,
                List.of(KEY_1, KEY_2)));

        // then
        assertEquals(0, inserted, "重复登记不得新增归属行");
        verify(mapper, times(1)).insertBatchIfAbsent(anyList());
        verifyNoMoreInteractions(mapper);
    }

    /**
     * 场景：登记入参缺失任一归属要素（kbId / chunkId / 分类 / 键集合为空或全为空白）。
     * 预期：短路返回 0，完全不下发 SQL。
     */
    @Test
    void should_skipSqlAndReturnZero_when_registerAll_given_blankArguments() {
        // when & then
        assertEquals(0, repository.registerAll(null, CHUNK_ID, CACHE_TYPE, List.of(KEY_1)));
        assertEquals(0, repository.registerAll(KB_ID, null, CACHE_TYPE, List.of(KEY_1)));
        assertEquals(0, repository.registerAll(KB_ID, CHUNK_ID, null, List.of(KEY_1)));
        assertEquals(0, repository.registerAll(KB_ID, CHUNK_ID, CACHE_TYPE, List.of()));
        assertEquals(0, repository.registerAll(KB_ID, CHUNK_ID, CACHE_TYPE, null));
        assertEquals(0, repository.registerAll(KB_ID, CHUNK_ID, CACHE_TYPE, Arrays.asList(" ", null, "")));
        verify(mapper, never()).insertBatchIfAbsent(anyList());
        verifyNoMoreInteractions(mapper);
    }

    /**
     * 场景：单分块续抽产生超过单批上限的缓存键。
     * 预期：按批次大小分片多次下发，返回值为各批新增行数之和。
     */
    @Test
    void should_splitIntoBatches_when_registerAll_given_keysExceedingBatchSize() {
        // given
        List<String> keys = new ArrayList<>(keysOf(REGISTER_BATCH_SIZE + 1));
        when(mapper.insertBatchIfAbsent(anyList())).thenReturn(REGISTER_BATCH_SIZE, 1);

        // when
        int inserted = repository.registerAll(KB_ID, CHUNK_ID, CACHE_TYPE, keys);

        // then
        assertEquals(REGISTER_BATCH_SIZE + 1, inserted);
        ArgumentCaptor<List<ChunkExtractCacheEntity>> captor = ArgumentCaptor.captor();
        verify(mapper, times(2)).insertBatchIfAbsent(captor.capture());
        assertEquals(REGISTER_BATCH_SIZE, captor.getAllValues().get(0).size(), "首批必须按上限切分");
        assertEquals(1, captor.getAllValues().get(1).size(), "末批承接余量");
    }

    /**
     * 场景：查询一批分块用过的缓存键，其中某键被两个分块共用、某分块用过多键。
     * 预期：返回「分块 → 键集合」映射，多对多关系双向可见，入参分块去重后下查。
     */
    @Test
    void should_groupKeysByChunk_when_findCacheKeysByChunkIds_given_manyToManyAttribution() {
        // given
        when(mapper.selectByKbIdAndChunkIds(eq(KB_ID), eq(CACHE_TYPE.name()), anyList())).thenReturn(List.of(
                rowOf(CHUNK_ID, KEY_1), rowOf(CHUNK_ID, KEY_2), rowOf(OTHER_CHUNK_ID, KEY_1)));

        // when
        Map<Long, Set<String>> keysByChunk = repository.findCacheKeysByChunkIds(KB_ID, CACHE_TYPE,
                List.of(CHUNK_ID, OTHER_CHUNK_ID, CHUNK_ID));

        // then
        assertEquals(2, keysByChunk.size());
        assertEquals(Set.of(KEY_1, KEY_2), keysByChunk.get(CHUNK_ID), "同一分块多轮抽取的多个键须全部可见");
        assertEquals(Set.of(KEY_1), keysByChunk.get(OTHER_CHUNK_ID), "共用键须在两个分块下各自可见");
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.captor();
        verify(mapper).selectByKbIdAndChunkIds(eq(KB_ID), eq(CACHE_TYPE.name()), captor.capture());
        assertEquals(List.of(CHUNK_ID, OTHER_CHUNK_ID), captor.getValue(), "入参分块必须去重后下查");
    }

    /**
     * 场景：追溯查询入参缺失（kbId / 分类 / 分块集合为空）。
     * 预期：返回空映射且不下发 SQL。
     */
    @Test
    void should_returnEmptyMapWithoutSql_when_findCacheKeysByChunkIds_given_blankArguments() {
        // when & then
        assertTrue(repository.findCacheKeysByChunkIds(null, CACHE_TYPE, List.of(CHUNK_ID)).isEmpty());
        assertTrue(repository.findCacheKeysByChunkIds(KB_ID, null, List.of(CHUNK_ID)).isEmpty());
        assertTrue(repository.findCacheKeysByChunkIds(KB_ID, CACHE_TYPE, List.of()).isEmpty());
        verify(mapper, never()).selectByKbIdAndChunkIds(any(), any(), anyList());
    }

    /**
     * 场景：分块存在但无任何归属记录。
     * 预期：返回空映射（不出现该分块键），供上层据此判定重放不可用并降级。
     */
    @Test
    void should_returnEmptyMap_when_findCacheKeysByChunkIds_given_chunkWithoutAttribution() {
        // given
        when(mapper.selectByKbIdAndChunkIds(eq(KB_ID), eq(CACHE_TYPE.name()), anyList())).thenReturn(List.of());

        // when
        Map<Long, Set<String>> keysByChunk = repository.findCacheKeysByChunkIds(KB_ID, CACHE_TYPE,
                List.of(CHUNK_ID));

        // then
        assertTrue(keysByChunk.isEmpty(), "无归属分块不得凭空造键");
    }

    /**
     * 场景：删除链清理本批分块的归属行。
     * 预期：以 kbId + 去重后的分块集合委托物理删除，返回受影响行数。
     */
    @Test
    void should_delegatePhysicalDeleteWithDedupedIds_when_deleteByChunkIds_given_chunkIds() {
        // given
        when(mapper.deleteByKbIdAndChunkIds(eq(KB_ID), anyList())).thenReturn(2);

        // when
        int deleted = repository.deleteByChunkIds(KB_ID, List.of(CHUNK_ID, OTHER_CHUNK_ID, CHUNK_ID));

        // then
        assertEquals(2, deleted);
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.captor();
        verify(mapper, times(1)).deleteByKbIdAndChunkIds(eq(KB_ID), captor.capture());
        assertEquals(List.of(CHUNK_ID, OTHER_CHUNK_ID), captor.getValue(), "入参分块必须去重后删除");
    }

    /**
     * 场景：删除归属入参缺失（kbId 为空 / 分块集合为空）与重复执行（受影响 0 行）。
     * 预期：空参短路返回 0 且不下发 SQL；重复执行零影响静默收敛。
     */
    @Test
    void should_returnZeroWithoutSql_when_deleteByChunkIds_given_blankArgumentsAndReplay() {
        // given：第二次执行（归属已删）
        when(mapper.deleteByKbIdAndChunkIds(eq(KB_ID), anyList())).thenReturn(0);

        // when & then：空参短路
        assertEquals(0, repository.deleteByChunkIds(null, List.of(CHUNK_ID)));
        assertEquals(0, repository.deleteByChunkIds(KB_ID, List.of()));
        assertEquals(0, repository.deleteByChunkIds(KB_ID, null));
        // when & then：重入零影响
        assertEquals(0, repository.deleteByChunkIds(KB_ID, List.of(CHUNK_ID)));
        verify(mapper, times(1)).deleteByKbIdAndChunkIds(eq(KB_ID), anyList());
        verifyNoMoreInteractions(mapper);
    }

    /**
     * 场景：本批归属已删除，对追溯到的键做引用判定（其中 KEY_1 仍被其他存活分块引用）。
     * 预期：只返回仍被引用的键，未返回者即引用归零可回收；入参键去重去空白后下发。
     */
    @Test
    void should_returnOnlyStillReferencedKeys_when_findReferencedKeys_given_sharedAndExhaustedKeys() {
        // given
        when(mapper.selectReferencedKeys(eq(KB_ID), eq(CACHE_TYPE.name()), anyList())).thenReturn(List.of(KEY_1));

        // when
        Set<String> referenced = repository.findReferencedKeys(KB_ID, CACHE_TYPE,
                Arrays.asList(KEY_1, KEY_2, KEY_3, KEY_1, " "));

        // then
        assertEquals(Set.of(KEY_1), referenced, "只返回归属表中仍存在的键，归零键不得出现");
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.captor();
        verify(mapper).selectReferencedKeys(eq(KB_ID), eq(CACHE_TYPE.name()), captor.capture());
        assertEquals(List.of(KEY_1, KEY_2, KEY_3), captor.getValue(), "待判定键必须去重去空白后下发");
    }

    /**
     * 场景：追溯到的键全部随本批归属归零（查询无命中）。
     * 预期：返回空集合，供调用方回收全部对应缓存行。
     */
    @Test
    void should_returnEmptySet_when_findReferencedKeys_given_allKeysExhausted() {
        // given
        when(mapper.selectReferencedKeys(eq(KB_ID), eq(CACHE_TYPE.name()), anyList())).thenReturn(List.of());

        // when
        Set<String> referenced = repository.findReferencedKeys(KB_ID, CACHE_TYPE, List.of(KEY_2, KEY_3));

        // then
        assertTrue(referenced.isEmpty());
    }

    /**
     * 场景：引用判定入参缺失（kbId / 分类为空、键集合为空或全为空白串）。
     * 预期：返回空集合且不下发 SQL——MUST NOT 因入参为空而把「查不到」误当「无引用」。
     */
    @Test
    void should_skipSqlAndReturnEmpty_when_findReferencedKeys_given_blankArguments() {
        // given：键集合含空白与 null
        Collection<String> blankKeys = Arrays.asList(" ", null, "");

        // when & then
        assertTrue(repository.findReferencedKeys(null, CACHE_TYPE, List.of(KEY_1)).isEmpty());
        assertTrue(repository.findReferencedKeys(KB_ID, null, List.of(KEY_1)).isEmpty());
        assertTrue(repository.findReferencedKeys(KB_ID, CACHE_TYPE, List.of()).isEmpty());
        assertTrue(repository.findReferencedKeys(KB_ID, CACHE_TYPE, blankKeys).isEmpty());
        assertTrue(repository.findReferencedKeys(KB_ID, CACHE_TYPE, null).isEmpty());
        verify(mapper, never()).selectReferencedKeys(any(), any(), anyList());
    }
}
