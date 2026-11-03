package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkRepresentation;
import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkScoreHit;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkBm25Hit;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkVectorEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkMapper;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkVectorMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcChunkRepresentationRepository} 仓储实现单测（mock chunk_vector Mapper 与 chunk Mapper）。
 * <p>写入侧：仅落向量影子表，全文检索无派生表（BM25 索引随主表正文自动维护）。</p>
 * <p>读侧口径：两条检索通道均下推「仅已处理文档切片可见」的状态过滤
 * （{@code DocumentStatus.PROCESSED} 枚举名，单一真相源）；BM25 一路委托
 * {@link ChunkMapper#searchByBm25} 并原样透传其非负分。</p>
 */
@ExtendWith(MockitoExtension.class)
class JdbcChunkRepresentationRepositoryTest {

    private static final String VECTOR = "[0.1,0.2,0.3]";

    /** 编辑后的向量字面量（UPSERT 覆盖分支） */
    private static final String REVISED_VECTOR = "[0.4,0.5,0.6]";

    @Mock
    private ChunkVectorMapper vectorMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @InjectMocks
    private JdbcChunkRepresentationRepository repository;

    private static ChunkRepresentation representationOf(long chunkId, String embedding) {
        return ChunkRepresentation.create(1L, 11L, chunkId, embedding);
    }

    private static ChunkBm25Hit bm25HitOf(long chunkId, double score) {
        ChunkBm25Hit hit = new ChunkBm25Hit();
        hit.setChunkId(chunkId);
        hit.setScore(score);
        return hit;
    }

    @Test
    void should_writeVectorRowsOnly_when_saveBatch_given_representationsWithVector() {
        // given：其中一条向量字面量空白
        List<ChunkRepresentation> representations = List.of(
                representationOf(2001L, VECTOR),
                representationOf(2002L, VECTOR),
                representationOf(2003L, "  "));

        // when
        repository.saveBatch(representations, "7");

        // then：仅有向量的两条落库，空白字面量被跳过；全文无任何应用侧写入
        ArgumentCaptor<List<ChunkVectorEntity>> vectorCaptor = ArgumentCaptor.captor();
        verify(vectorMapper).insertBatch(vectorCaptor.capture());
        List<ChunkVectorEntity> vectorRows = vectorCaptor.getValue();
        assertEquals(2, vectorRows.size());
        assertEquals(2001L, vectorRows.get(0).getChunkId());
        assertEquals(VECTOR, vectorRows.get(0).getChunkVector());
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void should_fillAuditFields_when_saveBatch_given_batchInsertWithoutAutoFill() {
        // given
        List<ChunkRepresentation> representations = List.of(representationOf(2001L, VECTOR));

        // when
        repository.saveBatch(representations, null);

        // then：自定义批量 SQL 不触发自动填充，需显式补齐 audit 字段；操作人缺省回落系统账号
        ArgumentCaptor<List<ChunkVectorEntity>> vectorCaptor = ArgumentCaptor.captor();
        verify(vectorMapper).insertBatch(vectorCaptor.capture());
        ChunkVectorEntity vectorRow = vectorCaptor.getValue().get(0);
        assertEquals("system", vectorRow.getCreatedBy());
        assertEquals("system", vectorRow.getUpdatedBy());
        assertNotNull(vectorRow.getCreatedAt());
        assertNotNull(vectorRow.getUpdatedAt());
        assertEquals(vectorRow.getCreatedAt(), vectorRow.getUpdatedAt());
        assertEquals(1L, vectorRow.getKbId());
        assertEquals(11L, vectorRow.getDocumentId());
    }

    @Test
    void should_splitIntoBatches_when_saveBatch_given_overBatchSize() {
        // given
        List<ChunkRepresentation> representations = IntStream.rangeClosed(1, 1200)
                .mapToObj(index -> representationOf(2000L + index, VECTOR))
                .toList();

        // when
        repository.saveBatch(representations, "7");

        // then：按 500 条/批拆分为 3 次批量写入
        verify(vectorMapper, times(3)).insertBatch(anyList());
    }

    @Test
    void should_writeNothing_when_saveBatch_given_emptyRepresentations() {
        // when
        repository.saveBatch(List.of(), "7");

        // then
        verify(vectorMapper, never()).insertBatch(anyList());
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void should_writeNothing_when_saveBatch_given_allEmptyRepresentations() {
        // given：向量字面量全部缺失
        List<ChunkRepresentation> representations = List.of(representationOf(2001L, null));

        // when
        repository.saveBatch(representations, "7");

        // then：收集结果为空集合，BatchSplitter 不产生任何批次
        verify(vectorMapper, never()).insertBatch(anyList());
    }

    @Test
    void should_upsertVectorRow_when_upsert_given_validRepresentation() {
        // given：人工新增切片首次获得向量表示行
        ChunkRepresentation representation = representationOf(2001L, VECTOR);

        // when
        repository.upsert(representation, "7");

        // then
        ArgumentCaptor<ChunkVectorEntity> vectorCaptor = ArgumentCaptor.captor();
        verify(vectorMapper).upsert(vectorCaptor.capture());
        ChunkVectorEntity vectorRow = vectorCaptor.getValue();
        assertEquals(2001L, vectorRow.getChunkId());
        assertEquals(1L, vectorRow.getKbId());
        assertEquals(11L, vectorRow.getDocumentId());
        assertEquals(VECTOR, vectorRow.getChunkVector());
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void should_fillAuditFields_when_upsert_given_singleUpsertWithoutAutoFill() {
        // given
        ChunkRepresentation representation = representationOf(2001L, VECTOR);

        // when
        repository.upsert(representation, null);

        // then：自定义注解 SQL 不触发自动填充，插入即最新态，操作人缺省回落系统账号
        ArgumentCaptor<ChunkVectorEntity> vectorCaptor = ArgumentCaptor.captor();
        verify(vectorMapper).upsert(vectorCaptor.capture());
        ChunkVectorEntity vectorRow = vectorCaptor.getValue();
        assertEquals("system", vectorRow.getCreatedBy());
        assertEquals("system", vectorRow.getUpdatedBy());
        assertNotNull(vectorRow.getCreatedAt());
        assertNotNull(vectorRow.getUpdatedAt());
    }

    @Test
    void should_overwriteSameChunkId_when_upsert_given_repeatedEdits() {
        // given：同一切片连续两次编辑（表示行已存在，走 ON CONFLICT 覆盖分支）
        repository.upsert(representationOf(2001L, VECTOR), "7");

        // when
        repository.upsert(representationOf(2001L, REVISED_VECTOR), "8");

        // then：两次均为 UPSERT 单语句，覆盖写同一 chunk_id 并刷新更新人
        ArgumentCaptor<ChunkVectorEntity> vectorCaptor = ArgumentCaptor.captor();
        verify(vectorMapper, times(2)).upsert(vectorCaptor.capture());
        List<ChunkVectorEntity> vectorRows = vectorCaptor.getAllValues();
        assertEquals(2001L, vectorRows.get(1).getChunkId());
        assertEquals(REVISED_VECTOR, vectorRows.get(1).getChunkVector());
        assertEquals("8", vectorRows.get(1).getUpdatedBy());
    }

    @Test
    void should_writeNothing_when_upsert_given_blankVector() {
        // given：未配置嵌入模型的降级口径（向量字面量空白）
        ChunkRepresentation representation = representationOf(2001L, "  ");

        // when
        repository.upsert(representation, "7");

        // then：无派生表示可写，零 mapper 交互
        verify(vectorMapper, never()).upsert(any(ChunkVectorEntity.class));
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void should_writeNothing_when_upsert_given_nullRepresentation() {
        // when：入参为空
        repository.upsert(null, "7");

        // then：空入参短路，零 mapper 交互且不抛异常
        verify(vectorMapper, never()).upsert(any(ChunkVectorEntity.class));
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void should_deleteOnlyVectorRows_when_deleteByChunkIds_given_ids() {
        // when
        repository.deleteByChunkIds(List.of(2001L, 2002L));

        // then：全文索引随 chunk 行物理删除自动收敛，无额外清理
        verify(vectorMapper).deleteByChunkIds(List.of(2001L, 2002L));
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void should_deleteOnlyVectorRows_when_deleteByDocumentAndKb_given_scopes() {
        // when
        repository.deleteByDocumentId(11L);
        repository.deleteByKbId(1L);

        // then
        verify(vectorMapper).deleteByDocumentId(11L);
        verify(vectorMapper).deleteByKbId(1L);
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void should_delegateToChunkMapper_when_searchByKeywords_given_validQuery() {
        // given
        when(chunkMapper.searchByBm25(1L, "权限管理", 10, DocumentStatus.PROCESSED.name()))
                .thenReturn(List.of());

        // when
        repository.searchByKeywords(1L, "权限管理", 10);

        // then：可见性过滤取值取枚举名；BM25 索引由存储侧承载，无需传分词配置名
        verify(chunkMapper).searchByBm25(1L, "权限管理", 10, DocumentStatus.PROCESSED.name());
        verifyNoInteractions(vectorMapper);
    }

    @Test
    void should_mapBm25HitsToScoreHits_when_searchByKeywords_given_hits() {
        // given：SQL 出口已取反，分值为非负且越大越相关
        when(chunkMapper.searchByBm25(eq(1L), eq("权限管理"), eq(10), anyString()))
                .thenReturn(List.of(bm25HitOf(2001L, 3.5D), bm25HitOf(2002L, 1.25D)));

        // when
        List<ChunkScoreHit> hits = repository.searchByKeywords(1L, "权限管理", 10);

        // then
        assertEquals(2, hits.size());
        assertEquals(2001L, hits.get(0).chunkId());
        assertEquals(3.5D, hits.get(0).score());
        assertEquals(1.25D, hits.get(1).score());
    }

    @Test
    void should_defaultZeroScore_when_searchByKeywords_given_nullScore() {
        // given：分数缺失的脏数据按 0 处理，不抛异常
        ChunkBm25Hit hit = new ChunkBm25Hit();
        hit.setChunkId(2001L);
        when(chunkMapper.searchByBm25(eq(1L), eq("权限管理"), eq(10), anyString()))
                .thenReturn(List.of(hit));

        // when
        List<ChunkScoreHit> hits = repository.searchByKeywords(1L, "权限管理", 10);

        // then
        assertEquals(1, hits.size());
        assertEquals(0.0D, hits.get(0).score());
    }

    @Test
    void should_returnEmptyWithoutQuery_when_searchByKeywords_given_blankQuery() {
        // when
        List<?> hits = repository.searchByKeywords(1L, "  ", 10);

        // then：入参非法短路，零存储交互
        assertEquals(0, hits.size());
        verify(chunkMapper, never()).searchByBm25(eq(1L), eq("  "), eq(10), anyString());
    }

    @Test
    void should_passVisibilityStatus_when_searchByVector_given_validQuery() {
        // given
        when(vectorMapper.searchByVector(1L, 0.3D, VECTOR, 10, DocumentStatus.PROCESSED.name()))
                .thenReturn(List.of());

        // when
        repository.searchByVector(1L, 0.3D, VECTOR, 10);

        // then：向量通道同样只召回已处理文档的切片
        verify(vectorMapper).searchByVector(1L, 0.3D, VECTOR, 10, DocumentStatus.PROCESSED.name());
    }
}