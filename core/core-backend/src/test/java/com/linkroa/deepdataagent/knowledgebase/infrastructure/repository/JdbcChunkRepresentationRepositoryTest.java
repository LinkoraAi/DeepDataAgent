package com.linkroa.deepdataagent.knowledgebase.infrastructure.repository;

import com.linkroa.deepdataagent.knowledgebase.domain.model.ChunkRepresentation;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkTsvEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.ChunkVectorEntity;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkTsvMapper;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkVectorMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcChunkRepresentationRepository} 仓储实现单测（mock chunk_vector / chunk_tsv Mapper）。
 * <p>后口径：全文影子行携带切片原文（chunkContent），
 * tsvector 由插入 SQL 内 to_tsvector 现算；写入与查询共用
 * {@link ChunkTsvMapper#FULLTEXT_TS_CONFIG} 配置名单点。</p>
 * <p>读侧口径：两条检索通道均下推「仅已处理文档切片可见」的状态过滤
 * （{@code DocumentStatus.PROCESSED} 枚举名，单一真相源）。</p>
 */
@ExtendWith(MockitoExtension.class)
class JdbcChunkRepresentationRepositoryTest {

    private static final String VECTOR = "[0.1,0.2,0.3]";

    /** 切片原文（全文现算输入），不再是对预分词字面量的断言 */
    private static final String CONTENT = "权限管理手册";

    /** 编辑后的向量字面量（UPSERT 覆盖分支） */
    private static final String REVISED_VECTOR = "[0.4,0.5,0.6]";

    /** 编辑后的切片原文（UPSERT 覆盖分支） */
    private static final String REVISED_CONTENT = "权限管理手册（修订）";

    @Mock
    private ChunkVectorMapper vectorMapper;

    @Mock
    private ChunkTsvMapper tsvMapper;

    @InjectMocks
    private JdbcChunkRepresentationRepository repository;

    private static ChunkRepresentation representationOf(long chunkId, String embedding, String chunkContent) {
        return ChunkRepresentation.create(1L, 11L, chunkId, embedding, chunkContent);
    }

    @Test
    void should_writeBothShadowTables_when_saveBatch_given_representationsWithVectorAndContent() {
        // given
        List<ChunkRepresentation> representations = List.of(
                representationOf(2001L, VECTOR, CONTENT),
                representationOf(2002L, VECTOR, null),
                representationOf(2003L, null, CONTENT));

        // when
        repository.saveBatch(representations, "7");

        // then：向量为空字面量、原文为空的影子行被跳过，两张表各自收集
        ArgumentCaptor<List<ChunkVectorEntity>> vectorCaptor = ArgumentCaptor.captor();
        verify(vectorMapper).insertBatch(vectorCaptor.capture());
        List<ChunkVectorEntity> vectorRows = vectorCaptor.getValue();
        assertEquals(2, vectorRows.size());
        assertEquals(2001L, vectorRows.get(0).getChunkId());
        assertEquals(VECTOR, vectorRows.get(0).getChunkVector());

        ArgumentCaptor<List<ChunkTsvEntity>> tsvCaptor = ArgumentCaptor.captor();
        verify(tsvMapper).insertBatch(tsvCaptor.capture(), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
        List<ChunkTsvEntity> tsvRows = tsvCaptor.getValue();
        assertEquals(2, tsvRows.size());
        assertEquals(2001L, tsvRows.get(0).getChunkId());
        assertEquals(CONTENT, tsvRows.get(1).getChunkContent());
    }

    @Test
    void should_fillAuditFields_when_saveBatch_given_batchInsertWithoutAutoFill() {
        // given
        List<ChunkRepresentation> representations = List.of(representationOf(2001L, VECTOR, CONTENT));

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

        ArgumentCaptor<List<ChunkTsvEntity>> tsvCaptor = ArgumentCaptor.captor();
        verify(tsvMapper).insertBatch(tsvCaptor.capture(), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
        assertEquals("system", tsvCaptor.getValue().get(0).getCreatedBy());
    }

    @Test
    void should_splitIntoBatches_when_saveBatch_given_overBatchSize() {
        // given
        List<ChunkRepresentation> representations = IntStream.rangeClosed(1, 1200)
                .mapToObj(index -> representationOf(2000L + index, VECTOR, CONTENT))
                .toList();

        // when
        repository.saveBatch(representations, "7");

        // then：两张表均按 500 条/批拆分为 3 次批量写入（仅 tsv 侧新增配置名入参）
        verify(vectorMapper, times(3)).insertBatch(anyList());
        verify(tsvMapper, times(3)).insertBatch(anyList(), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
    }

    @Test
    void should_writeNothing_when_saveBatch_given_emptyRepresentations() {
        // when
        repository.saveBatch(List.of(), "7");

        // then
        verify(vectorMapper, never()).insertBatch(anyList());
        verify(tsvMapper, never()).insertBatch(anyList(), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
    }

    @Test
    void should_skipBothTables_when_saveBatch_given_allEmptyRepresentations() {
        // given
        List<ChunkRepresentation> representations = List.of(representationOf(2001L, null, null));

        // when
        repository.saveBatch(representations, "7");

        // then：收集结果为空集合，BatchSplitter 不产生任何批次
        verify(vectorMapper, never()).insertBatch(anyList());
        verify(tsvMapper, never()).insertBatch(anyList(), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
    }

    @Test
    void should_upsertVectorThenTsv_when_upsert_given_vectorAndContent() {
        // given：人工新增切片首次获得表示行（两张表均无既有行）
        ChunkRepresentation representation = representationOf(2001L, VECTOR, CONTENT);

        // when
        repository.upsert(representation, "7");

        // then：固定序「先向量、后全文」，两张表各写一行
        ArgumentCaptor<ChunkVectorEntity> vectorCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<ChunkTsvEntity> tsvCaptor = ArgumentCaptor.captor();
        InOrder inOrder = inOrder(vectorMapper, tsvMapper);
        inOrder.verify(vectorMapper).upsert(vectorCaptor.capture());
        inOrder.verify(tsvMapper).upsert(tsvCaptor.capture(), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));

        ChunkVectorEntity vectorRow = vectorCaptor.getValue();
        assertEquals(2001L, vectorRow.getChunkId());
        assertEquals(1L, vectorRow.getKbId());
        assertEquals(11L, vectorRow.getDocumentId());
        assertEquals(VECTOR, vectorRow.getChunkVector());

        ChunkTsvEntity tsvRow = tsvCaptor.getValue();
        assertEquals(2001L, tsvRow.getChunkId());
        assertEquals(1L, tsvRow.getKbId());
        assertEquals(11L, tsvRow.getDocumentId());
        assertEquals(CONTENT, tsvRow.getChunkContent());
    }

    @Test
    void should_fillAuditFields_when_upsert_given_singleUpsertWithoutAutoFill() {
        // given
        ChunkRepresentation representation = representationOf(2001L, VECTOR, CONTENT);

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
        assertEquals(vectorRow.getCreatedAt(), vectorRow.getUpdatedAt());

        ArgumentCaptor<ChunkTsvEntity> tsvCaptor = ArgumentCaptor.captor();
        verify(tsvMapper).upsert(tsvCaptor.capture(), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
        ChunkTsvEntity tsvRow = tsvCaptor.getValue();
        assertEquals("system", tsvRow.getCreatedBy());
        assertEquals("system", tsvRow.getUpdatedBy());
        assertNotNull(tsvRow.getCreatedAt());
        assertNotNull(tsvRow.getUpdatedAt());
    }

    @Test
    void should_overwriteSameChunkId_when_upsert_given_repeatedEdits() {
        // given：同一切片连续两次编辑（表示行已存在，走 ON CONFLICT 覆盖分支）
        ChunkRepresentation first = representationOf(2001L, VECTOR, CONTENT);

        // when
        repository.upsert(first, "7");
        repository.upsert(representationOf(2001L, REVISED_VECTOR, REVISED_CONTENT), "8");

        // then：两次均为 UPSERT 单语句（不依赖表示行预先存在），覆盖写同一 chunk_id 并刷新更新人
        ArgumentCaptor<ChunkVectorEntity> vectorCaptor = ArgumentCaptor.captor();
        verify(vectorMapper, times(2)).upsert(vectorCaptor.capture());
        List<ChunkVectorEntity> vectorRows = vectorCaptor.getAllValues();
        assertEquals(2001L, vectorRows.get(1).getChunkId());
        assertEquals(REVISED_VECTOR, vectorRows.get(1).getChunkVector());
        assertEquals("8", vectorRows.get(1).getUpdatedBy());

        ArgumentCaptor<ChunkTsvEntity> tsvCaptor = ArgumentCaptor.captor();
        verify(tsvMapper, times(2)).upsert(tsvCaptor.capture(), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
        List<ChunkTsvEntity> tsvRows = tsvCaptor.getAllValues();
        assertEquals(2001L, tsvRows.get(1).getChunkId());
        assertEquals(REVISED_CONTENT, tsvRows.get(1).getChunkContent());
        assertEquals("8", tsvRows.get(1).getUpdatedBy());
    }

    @Test
    void should_writeTsvOnly_when_upsert_given_blankVector() {
        // given：未配置嵌入模型的降级口径（向量字面量空白）
        ChunkRepresentation representation = representationOf(2001L, "  ", CONTENT);

        // when
        repository.upsert(representation, "7");

        // then：跳过向量行，全文表示照写
        verify(vectorMapper, never()).upsert(any(ChunkVectorEntity.class));
        verify(tsvMapper).upsert(any(ChunkTsvEntity.class), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
    }

    @Test
    void should_writeVectorOnly_when_upsert_given_blankChunkContent() {
        // given：切片原文为空（不落全文行）
        ChunkRepresentation representation = representationOf(2001L, VECTOR, null);

        // when
        repository.upsert(representation, "7");

        // then：跳过全文行，向量行照写
        verify(vectorMapper).upsert(any(ChunkVectorEntity.class));
        verify(tsvMapper, never()).upsert(any(ChunkTsvEntity.class), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
    }

    @Test
    void should_writeNothing_when_upsert_given_emptyRepresentation() {
        // given：向量与切片原文均缺失
        ChunkRepresentation representation = representationOf(2001L, null, null);

        // when
        repository.upsert(representation, "7");

        // then：空表示短路，零 mapper 交互
        verify(vectorMapper, never()).upsert(any(ChunkVectorEntity.class));
        verify(tsvMapper, never()).upsert(any(ChunkTsvEntity.class), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
    }

    @Test
    void should_writeNothing_when_upsert_given_nullRepresentation() {
        // when：入参为空
        repository.upsert(null, "7");

        // then：空入参短路，零 mapper 交互且不抛异常
        verify(vectorMapper, never()).upsert(any(ChunkVectorEntity.class));
        verify(tsvMapper, never()).upsert(any(ChunkTsvEntity.class), eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG));
    }

    @Test
    void should_delegateDeletes_when_deleteOperations_given_variousScopes() {
        // when
        repository.deleteByChunkIds(List.of(2001L, 2002L));
        repository.deleteByDocumentId(11L);
        repository.deleteByKbId(1L);

        // then
        verify(vectorMapper).deleteByChunkIds(List.of(2001L, 2002L));
        verify(tsvMapper).deleteByChunkIds(List.of(2001L, 2002L));
        verify(vectorMapper).deleteByDocumentId(11L);
        verify(tsvMapper).deleteByDocumentId(11L);
        verify(vectorMapper).deleteByKbId(1L);
        verify(tsvMapper).deleteByKbId(1L);
    }

    @Test
    void should_passSingleSourceTsConfig_when_searchByKeywords_given_validQuery() {
        // given
        when(tsvMapper.searchByKeywords(1L, "权限管理", 10, ChunkTsvMapper.FULLTEXT_TS_CONFIG,
                DocumentStatus.PROCESSED.name())).thenReturn(List.of());

        // when
        repository.searchByKeywords(1L, "权限管理", 10);

        // then：查询侧与写入侧共用同一配置名单点；可见性过滤取值取枚举名（仅已处理文档切片可召回）
        verify(tsvMapper).searchByKeywords(1L, "权限管理", 10, ChunkTsvMapper.FULLTEXT_TS_CONFIG,
                DocumentStatus.PROCESSED.name());
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

    @Test
    void should_returnEmptyWithoutQuery_when_searchByKeywords_given_blankQuery() {
        // when
        List<?> hits = repository.searchByKeywords(1L, "  ", 10);

        // then：入参非法短路，零存储交互
        assertEquals(0, hits.size());
        verify(tsvMapper, never()).searchByKeywords(eq(1L), eq("  "), eq(10),
                eq(ChunkTsvMapper.FULLTEXT_TS_CONFIG), eq(DocumentStatus.PROCESSED.name()));
    }
}
