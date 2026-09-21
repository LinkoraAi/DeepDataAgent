package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.linkroa.deepdataagent.rag.domain.model.RelationLedgerSnapshot;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.RelationInfoVectorMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.RelationLedgerOverlapProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcRelationInfoVectorRepository} 收敛端口单测（mock MyBatis Mapper，聚焦
 * {@code removeChunkContributionsAndPrune}）。
 * <p>覆盖：账本剔空物理删的三语句委托（①图边→②向量行→③账本收缩固定顺序，图边按双向端点
 * OR 匹配关联向量行，计数取②，三语句均以 {@code kbId} 收窄扫描面）、kbId 为空的防御短路
 * （返回 0 且零 DB 交互）、null 元素与重复元素前置归一化（防 NOT IN 空值毒化误删存活
 * 条目）、空集合与仅含 null 集合零交互、重复收敛（二次调用与已剔账本零交集→三语句零影响）
 * 静默返回 0。注解 SQL 文本语义按项目惯例不在单测校验。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class JdbcRelationInfoVectorRepositoryTest {

    /** 测试用知识库ID（扫描面收窄条件） */
    private static final Long KB_ID = 7L;

    /** 被清退的切片ID批次（已归一化形态） */
    private static final List<Long> CHUNK_IDS = List.of(101L, 102L);

    /** 关系向量 Mapper Mock */
    @Mock
    private RelationInfoVectorMapper mapper;

    /** 被测仓储 */
    @InjectMocks
    private JdbcRelationInfoVectorRepository repository;

    /**
     * 场景：批次内关系账本被剔空（图边 + 向量行各物理删 3 行），另有 7 行存活账本收缩。
     * 预期：返回向量行删除计数 3；三语句按①图边→②向量行→③收缩固定顺序委托且均带 kbId，无其它交互。
     */
    @Test
    void should_returnVectorDeletedCountWithFixedOrder_when_removeChunkContributionsAndPrune_given_prunedAndSurvivingRows() {
        // given
        when(mapper.physicalDeletePrunedGraphEdges(KB_ID, CHUNK_IDS)).thenReturn(3);
        when(mapper.physicalDeletePrunedVectors(KB_ID, CHUNK_IDS)).thenReturn(3);
        when(mapper.shrinkChunkContributions(KB_ID, CHUNK_IDS)).thenReturn(7);

        // when
        int removed = repository.removeChunkContributionsAndPrune(KB_ID, CHUNK_IDS);

        // then：计数口径 = 向量行物理删除数；顺序固定（①依赖向量行存在做双向关联判定）
        assertEquals(3, removed, "返回值应为向量行物理删除计数，而非图边或收缩数");
        InOrder inOrder = inOrder(mapper);
        inOrder.verify(mapper).physicalDeletePrunedGraphEdges(KB_ID, CHUNK_IDS);
        inOrder.verify(mapper).physicalDeletePrunedVectors(KB_ID, CHUNK_IDS);
        inOrder.verify(mapper).shrinkChunkContributions(KB_ID, CHUNK_IDS);
        verifyNoMoreInteractions(mapper);
    }

    /**
     * 场景：入参含 null 元素与重复元素。
     * 预期：下发 Mapper 前完成去 null 去重（防 NOT IN 空值毒化），三语句均收到 kbId + 归一化批次。
     */
    @Test
    void should_filterNullAndDeduplicate_when_removeChunkContributionsAndPrune_given_dirtyChunkIds() {
        // given
        when(mapper.physicalDeletePrunedGraphEdges(eq(KB_ID), anyList())).thenReturn(0);
        when(mapper.physicalDeletePrunedVectors(eq(KB_ID), anyList())).thenReturn(0);
        when(mapper.shrinkChunkContributions(eq(KB_ID), anyList())).thenReturn(1);

        // when
        int removed = repository.removeChunkContributionsAndPrune(KB_ID, Arrays.asList(101L, null, 101L));

        // then：三语句均只收到 kbId + 去 null 去重后的 [101]
        assertEquals(0, removed);
        verify(mapper).physicalDeletePrunedGraphEdges(KB_ID, List.of(101L));
        verify(mapper).physicalDeletePrunedVectors(KB_ID, List.of(101L));
        verify(mapper).shrinkChunkContributions(KB_ID, List.of(101L));
        verifyNoMoreInteractions(mapper);
    }

    /**
     * 场景：重复收敛（第一次已剔除全部贡献，第二次账本与批次零交集）。
     * 预期：三语句均 0 行影响，静默返回 0，无异常（幂等可重入的委托层表现）。
     */
    @Test
    void should_returnZeroSilently_when_removeChunkContributionsAndPrune_given_repeatInvocationWithoutIntersection() {
        // given：三语句零影响
        when(mapper.physicalDeletePrunedGraphEdges(KB_ID, CHUNK_IDS)).thenReturn(0);
        when(mapper.physicalDeletePrunedVectors(KB_ID, CHUNK_IDS)).thenReturn(0);
        when(mapper.shrinkChunkContributions(KB_ID, CHUNK_IDS)).thenReturn(0);

        // when
        int removed = repository.removeChunkContributionsAndPrune(KB_ID, CHUNK_IDS);

        // then：三语句仍按固定顺序各执行一次，但全部零影响
        assertEquals(0, removed, "零交集重复收敛应返回 0");
        InOrder inOrder = inOrder(mapper);
        inOrder.verify(mapper).physicalDeletePrunedGraphEdges(KB_ID, CHUNK_IDS);
        inOrder.verify(mapper).physicalDeletePrunedVectors(KB_ID, CHUNK_IDS);
        inOrder.verify(mapper).shrinkChunkContributions(KB_ID, CHUNK_IDS);
        verifyNoMoreInteractions(mapper);
    }

    /**
     * 场景：空集合入参。
     * 预期：返回 0，零 DB 交互（契约要求空集合不产生任何写入）。
     */
    @Test
    void should_returnZeroWithoutAnyStatement_when_removeChunkContributionsAndPrune_given_emptyCollection() {
        // when
        int removed = repository.removeChunkContributionsAndPrune(KB_ID, List.of());

        // then
        assertEquals(0, removed);
        verifyNoInteractions(mapper);
    }

    /**
     * 场景：集合仅含 null 元素（归一化后为空）。
     * 预期：返回 0，零 DB 交互。
     */
    @Test
    void should_returnZeroWithoutAnyStatement_when_removeChunkContributionsAndPrune_given_onlyNullElements() {
        // when
        int removed = repository.removeChunkContributionsAndPrune(KB_ID, Arrays.<Long>asList(null, null));

        // then
        assertEquals(0, removed);
        verifyNoInteractions(mapper);
    }

    /**
     * 场景：kbId 为空（扫描面收窄条件必填，切片归属无从收窄）。
     * 预期：返回 0，零 DB 交互（不开任何收窄失效的清退语句）。
     */
    @Test
    void should_returnZeroWithoutAnyStatement_when_removeChunkContributionsAndPrune_given_nullKbId() {
        // when
        int removed = repository.removeChunkContributionsAndPrune(null, CHUNK_IDS);

        // then
        assertEquals(0, removed, "kbId 为空必须防御性返回 0");
        verifyNoInteractions(mapper);
    }

    /**
     * 场景：内容收口窄更新，守卫命中（Mapper 返回受影响 1 行）。
     * 预期：委托单条窄更新语句且返回 1；全程 MUST NOT 调用整行 {@code upsertBatch}——
     * 以此锁定「只更新内容类列、不整行覆盖账本列」的收口口径（账本列不变的写入侧保证）。
     */
    @Test
    void should_updateContentNarrowlyWithoutLedgerTouch_when_updateContentIfUnchanged_given_guardHit() {
        // given：无向端点对 Alice/Bob + 期望内容/新向量/守卫内容
        when(mapper.updateContentIfUnchanged(KB_ID, "Alice", "Bob", "expected-content", "[0.3,0.4]",
                "stale-content")).thenReturn(1);

        // when
        int affected = repository.updateContentIfUnchanged(KB_ID, "Alice", "Bob", "expected-content",
                new float[]{0.3f, 0.4f}, "stale-content");

        // then：受影响 1 行，且仅窄更新被委托、整行 upsert 零调用（账本列不被回卷）
        assertEquals(1, affected, "守卫命中应收口成功（受影响 1 行）");
        verify(mapper).updateContentIfUnchanged(KB_ID, "Alice", "Bob", "expected-content", "[0.3,0.4]",
                "stale-content");
        verify(mapper, never()).upsertBatch(anyList());
        verifyNoMoreInteractions(mapper);
    }

    /**
     * 场景：内容收口窄更新，守卫未命中（并发他人已改写内容，Mapper 返回受影响 0 行）。
     * 预期：返回 0 且无写入（调用方据此记为「守卫落空」跳过），仍不触碰整行 upsert。
     */
    @Test
    void should_returnZeroWithoutWrite_when_updateContentIfUnchanged_given_guardMiss() {
        // given：守卫未命中，Mapper 零影响
        when(mapper.updateContentIfUnchanged(KB_ID, "Alice", "Bob", "expected-content", "[0.3,0.4]",
                "stale-content")).thenReturn(0);

        // when
        int affected = repository.updateContentIfUnchanged(KB_ID, "Alice", "Bob", "expected-content",
                new float[]{0.3f, 0.4f}, "stale-content");

        // then
        assertEquals(0, affected, "守卫未命中应返回 0 行（视为跳过而非错误）");
        verify(mapper, never()).upsertBatch(anyList());
    }

    /**
     * 场景：kbId 为空（窄更新定位条件必填）。
     * 预期：返回 0，零 DB 交互。
     */
    @Test
    void should_returnZeroWithoutAnyStatement_when_updateContentIfUnchanged_given_nullKbId() {
        // when
        int affected = repository.updateContentIfUnchanged(null, "Alice", "Bob", "expected-content",
                new float[]{0.3f, 0.4f}, "stale-content");

        // then
        assertEquals(0, affected);
        verifyNoInteractions(mapper);
    }

    /**
     * 场景：整库清退首片即不满批（123 行 &lt; 1000）。
     * 预期：一片一次调用即止，返回该片的物理删除行数。
     */
    @Test
    void should_returnTotalDeletedAndStopAtFirstShortBatch_when_deleteByKbId_given_singleIncompleteBatch() {
        // given
        when(mapper.physicalDeleteByKbIdBatch(7L, 1000)).thenReturn(123);

        // when
        int deleted = repository.deleteByKbId(7L);

        // then
        assertEquals(123, deleted);
        verify(mapper, times(1)).physicalDeleteByKbIdBatch(7L, 1000);
    }

    /**
     * 场景：整库行数超过单片上限（两片满批 + 第三片不满批收尾）。
     * 预期：分片循环续圈至本片不满，返回各片行数合计。
     */
    @Test
    void should_keepLoopingUntilShortBatch_when_deleteByKbId_given_multipleFullBatches() {
        // given
        when(mapper.physicalDeleteByKbIdBatch(7L, 1000)).thenReturn(1000, 1000, 40);

        // when
        int deleted = repository.deleteByKbId(7L);

        // then
        assertEquals(2040, deleted);
        verify(mapper, times(3)).physicalDeleteByKbIdBatch(7L, 1000);
    }

    /**
     * 场景：kbId 为空。
     * 预期：返回 0，零 DB 交互。
     */
    @Test
    void should_returnZeroWithoutAnyStatement_when_deleteByKbId_given_nullKbId() {
        // when
        int deleted = repository.deleteByKbId(null);

        // then
        assertEquals(0, deleted);
        verifyNoInteractions(mapper);
    }

    /**
     * 场景：分片删除中数据源不可达。
     * 预期：异常原样上抛，由清退消费方决定退避重试（不在仓储层吞没）。
     */
    @Test
    void should_propagateException_when_deleteByKbId_given_mapperFailure() {
        // given
        when(mapper.physicalDeleteByKbIdBatch(7L, 1000))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        // when & then
        assertThrows(DataAccessResourceFailureException.class, () -> repository.deleteByKbId(7L));
    }

    /**
     * 场景：重建分类阶段的账本重叠查询（组 4 新增端口），历史反向落库行（Bob,Alice）。
     * 预期：入参去 null 去重后单条只读语句委托；端点对在快照侧归一为字典序 (Alice,Bob)，
     * 属性缺失归一空属性。
     */
    @Test
    void should_returnNormalizedLedgerSnapshots_when_findLedgerSnapshotsWithChunkOverlap_given_reverseRow() {
        // given：一行命中（历史反向向量行 (Bob,Alice)，账本 [101,205]）
        RelationLedgerOverlapProjection projection = new RelationLedgerOverlapProjection();
        projection.setSourceName("Bob");
        projection.setTargetName("Alice");
        projection.setContent("rel-content");
        projection.setContentVector("[0.5,0.6]");
        projection.setChunkIdsRaw("[101,205]");
        projection.setPropertiesRaw(null);
        projection.setGraphMissing(true);
        when(mapper.selectLedgerOverlappingChunks(KB_ID, List.of(101L))).thenReturn(List.of(projection));

        // when
        List<RelationLedgerSnapshot> snapshots = repository.findLedgerSnapshotsWithChunkOverlap(
                KB_ID, Arrays.asList(101L, null, 101L));

        // then：端点对归一为字典序，账本按落库原序承载（非入参集）
        assertEquals(1, snapshots.size());
        assertEquals(List.of("Alice", "Bob"), snapshots.get(0).normalizedPair());
        assertEquals(List.of(101L, 205L), snapshots.get(0).chunkIds());
        assertEquals(RelationProperties.empty(), snapshots.get(0).properties());
    }

    /**
     * 场景：账本重叠查询的空入参族（kbId 空 / 空集合 / 仅含 null）。
     * 预期：全部返回空列表且零 DB 交互。
     */
    @Test
    void should_returnEmptyListWithoutAnyStatement_when_findLedgerSnapshotsWithChunkOverlap_given_emptyInputs() {
        assertTrue(repository.findLedgerSnapshotsWithChunkOverlap(null, CHUNK_IDS).isEmpty());
        assertTrue(repository.findLedgerSnapshotsWithChunkOverlap(KB_ID, List.of()).isEmpty());
        assertTrue(repository.findLedgerSnapshotsWithChunkOverlap(KB_ID,
                java.util.Arrays.asList(null, null)).isEmpty());
        verifyNoInteractions(mapper);
    }

    /**
     * 场景：按端点对物理删关系向量行（重建写回转删除原语）。
     * 预期：正常入参委托双向 OR 单条 DELETE；kbId/端点缺失防御返回 0 且零 DB 交互。
     */
    @Test
    void should_delegateBidirectionalDelete_when_deleteByKbIdAndUnorderedPair_given_validAndBlankInputs() {
        // given
        when(mapper.physicalDeleteByKbIdAndUnorderedPair(KB_ID, "Alice", "Bob")).thenReturn(1);

        // when & then
        assertEquals(1, repository.deleteByKbIdAndUnorderedPair(KB_ID, "Alice", "Bob"));
        assertEquals(0, repository.deleteByKbIdAndUnorderedPair(null, "Alice", "Bob"));
        assertEquals(0, repository.deleteByKbIdAndUnorderedPair(KB_ID, "Alice", " "));
        verify(mapper).physicalDeleteByKbIdAndUnorderedPair(KB_ID, "Alice", "Bob");
        verifyNoMoreInteractions(mapper);
    }
}
