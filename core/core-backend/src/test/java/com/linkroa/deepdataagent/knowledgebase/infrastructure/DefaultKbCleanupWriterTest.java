package com.linkroa.deepdataagent.knowledgebase.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.application.service.ChunkPhysicalDeleteService;
import com.linkroa.deepdataagent.knowledgebase.application.service.MediaImageCleanupService;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.ChunkMapper;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultKbCleanupWriter} 单元测试（整库清退回写契约实现）。
 * <p>覆盖：彻底物理删体系下的标准取批与清退口径（普通查询取批 + 切片删除原语 + {@code deleteByIds}）、
 * 批次上限封顶、幂等可重入（已清退即空转返回 0）、「Storage 先删、DB 后删」四档序、入参非法拒绝。</p>
 * <p>口径说明：桶概念已退役——资产回收委托 {@code cleanupKnowledgeBaseImages(kbId)} 按整库前缀
 * {@code rag/{kbId}/} 单次清退（天然覆盖源文件与文档媒体段），不再从文档行发现桶集合，
 * 也不依赖 {@code DocumentRepository}（构造器已去掉该参数）。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class DefaultKbCleanupWriterTest {

    private static final Long KB_ID = 7L;
    private static final int BATCH = 500;

    /** 清退链的系统级操作人（无用户上下文，与原语审计口径缺省值同源） */
    private static final String SYSTEM_OPERATOR = "system";

    @Mock
    private MediaImageCleanupService mediaImageCleanupService;
    @Mock
    private ChunkMapper chunkMapper;
    @Mock
    private ChunkPhysicalDeleteService chunkPhysicalDeleteService;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DefaultKbCleanupWriter writer;

    @BeforeEach
    void setUp() {
        // 事务模板直通：单测聚焦批次语义与调用顺序，不验证真实事务边界
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void should_cleanupByWholeKbPrefix_when_cleanupAssets_given_validKnowledgeBaseId() {
        // given：整库前缀仅由 kbId 派生（桶概念已退役），委托服务单次清退

        // when
        writer.cleanupAssetsByKnowledgeBase(KB_ID);

        // then：远程 IO 委托恰好一次，且全程不触任何数据库清退批次
        verify(mediaImageCleanupService).cleanupKnowledgeBaseImages(KB_ID);
        verifyNoDbCleanupTouched();
    }

    @Test
    void should_propagateStorageFailure_when_cleanupAssets_given_objectStorageUnavailable() {
        // given：存储异常按可归因步骤原样上抛（消费方即时 markFailed、零重试），不吞没、不静默部分成功
        doThrow(new RuntimeException("对象存储不可达"))
                .when(mediaImageCleanupService).cleanupKnowledgeBaseImages(KB_ID);

        // when // then
        assertThrows(RuntimeException.class, () -> writer.cleanupAssetsByKnowledgeBase(KB_ID));
        verifyNoDbCleanupTouched();
    }

    @Test
    void should_throwIllegalArgument_when_cleanupAssets_given_nullKnowledgeBaseId() {
        // when // then：kbId 为空即拒绝，不触存储
        assertThrows(IllegalArgumentException.class, () -> writer.cleanupAssetsByKnowledgeBase(null));
        verify(mediaImageCleanupService, never()).cleanupKnowledgeBaseImages(any());
    }

    @Test
    void should_delegateToChunkDeletePrimitive_when_cleanupDerivedDataBatch_given_chunkIdsFound() {
        // given：标准普通查询按主键升序取一批切片 ID（无「绕过逻辑删」补丁）
        List<Long> chunkIds = List.of(11L, 12L);
        when(chunkMapper.selectIdsByKbId(KB_ID, BATCH)).thenReturn(chunkIds);

        // when
        int cleaned = writer.cleanupDerivedDataBatch(KB_ID, BATCH);

        // then：整批委托切片物理删除原语（3 参），整库场景不回写文档分块计数（false）；
        // 图谱收敛不再由调用方声明——原语按批次内切片来源数据自行推导，返回本批清退切片行数
        assertEquals(2, cleaned);
        verify(chunkPhysicalDeleteService).deleteChunks(chunkIds, SYSTEM_OPERATOR, false);
    }

    @Test
    void should_returnZeroAndSkipPrimitive_when_cleanupDerivedDataBatch_given_noChunkLeft() {
        // given：该库切片已清空（幂等重入即空转，清退续跑的收敛点）
        when(chunkMapper.selectIdsByKbId(KB_ID, BATCH)).thenReturn(List.of());

        // when
        int cleaned = writer.cleanupDerivedDataBatch(KB_ID, BATCH);

        // then
        assertEquals(0, cleaned);
        verify(chunkPhysicalDeleteService, never())
                .deleteChunks(anyCollection(), anyString(), anyBoolean());
    }

    @Test
    void should_returnZero_when_cleanupDerivedDataBatch_given_secondReplayAfterDrained() {
        // given：同一清退步骤被重删链重复调用——首轮取批清退、次轮取批为空
        when(chunkMapper.selectIdsByKbId(KB_ID, BATCH))
                .thenReturn(List.of(11L))
                .thenReturn(List.of());

        // when
        int firstRound = writer.cleanupDerivedDataBatch(KB_ID, BATCH);
        int secondRound = writer.cleanupDerivedDataBatch(KB_ID, BATCH);

        // then：仅首轮产生删除，次轮空转（重复执行不报错、不重复计删）
        assertEquals(1, firstRound);
        assertEquals(0, secondRound);
        verify(chunkPhysicalDeleteService).deleteChunks(List.of(11L), SYSTEM_OPERATOR, false);
    }

    @Test
    void should_clampBatchLimitToMax_when_cleanupDerivedDataBatch_given_oversizedBatchSize() {
        // given：批次大小超过事务规范上限（1000）时封顶解释
        when(chunkMapper.selectIdsByKbId(KB_ID, 1000)).thenReturn(List.of());

        // when
        int cleaned = writer.cleanupDerivedDataBatch(KB_ID, 5000);

        // then
        assertEquals(0, cleaned);
        verify(chunkMapper).selectIdsByKbId(KB_ID, 1000);
    }

    @Test
    void should_throwIllegalArgument_when_cleanupDerivedDataBatch_given_invalidParams() {
        // when // then：kbId 为空或批次非法均拒绝，不触库
        assertThrows(IllegalArgumentException.class, () -> writer.cleanupDerivedDataBatch(null, BATCH));
        assertThrows(IllegalArgumentException.class, () -> writer.cleanupDerivedDataBatch(KB_ID, 0));
        verify(chunkMapper, never()).selectIdsByKbId(any(), anyInt());
        verify(chunkPhysicalDeleteService, never())
                .deleteChunks(anyCollection(), anyString(), anyBoolean());
    }

    @Test
    void should_deleteDocumentRowsByIds_when_cleanupDocumentsBatch_given_documentIdsFound() {
        // given：按主键升序取一批文档 ID，单批小事务内标准 deleteByIds 即物理 DELETE
        List<Long> documentIds = List.of(31L, 32L, 33L);
        when(documentMapper.selectIdsByKbId(KB_ID, BATCH)).thenReturn(documentIds);
        when(documentMapper.deleteByIds(documentIds)).thenReturn(documentIds.size());

        // when
        int cleaned = writer.cleanupDocumentsBatch(KB_ID, BATCH);

        // then：本批文档清退完成（返回取批命中数，0 才代表已清空）
        assertEquals(3, cleaned);
        verify(transactionTemplate).execute(any());
        verify(documentMapper).deleteByIds(documentIds);
    }

    @Test
    void should_returnZeroWithoutTransaction_when_cleanupDocumentsBatch_given_noDocumentLeft() {
        // given：文档已清空（重删续跑至收敛点后幂等空转）
        when(documentMapper.selectIdsByKbId(KB_ID, BATCH)).thenReturn(List.of());

        // when
        int cleaned = writer.cleanupDocumentsBatch(KB_ID, BATCH);

        // then
        assertEquals(0, cleaned);
        verify(documentMapper, never()).deleteByIds(anyList());
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void should_throwIllegalArgument_when_cleanupDocumentsBatch_given_invalidParams() {
        // when // then
        assertThrows(IllegalArgumentException.class, () -> writer.cleanupDocumentsBatch(null, BATCH));
        assertThrows(IllegalArgumentException.class, () -> writer.cleanupDocumentsBatch(KB_ID, -1));
        verify(documentMapper, never()).selectIdsByKbId(any(), anyInt());
    }

    @Test
    void should_runStorageBeforeDbBatches_when_cleanupPhases_given_fullKbRetired() {
        // given：「Storage 先删、DB 后删」的完整四档推进（资产 → 切片批 → 文档批）
        List<Long> chunkIds = List.of(11L);
        List<Long> documentIds = List.of(31L);
        when(chunkMapper.selectIdsByKbId(KB_ID, BATCH)).thenReturn(chunkIds);
        when(documentMapper.selectIdsByKbId(KB_ID, BATCH)).thenReturn(documentIds);
        when(documentMapper.deleteByIds(documentIds)).thenReturn(1);

        // when：由消费方（RAG 清退编排）按序驱动本契约
        writer.cleanupAssetsByKnowledgeBase(KB_ID);
        writer.cleanupDerivedDataBatch(KB_ID, BATCH);
        writer.cleanupDocumentsBatch(KB_ID, BATCH);

        // then：资产回收（整库前缀单次清退）恒先于任何数据库清退批次，切片批恒先于文档批
        // （对象存储零事务内调用）
        InOrder inOrder = inOrder(mediaImageCleanupService, chunkMapper, chunkPhysicalDeleteService,
                documentMapper);
        inOrder.verify(mediaImageCleanupService).cleanupKnowledgeBaseImages(KB_ID);
        inOrder.verify(chunkMapper).selectIdsByKbId(KB_ID, BATCH);
        inOrder.verify(chunkPhysicalDeleteService).deleteChunks(chunkIds, SYSTEM_OPERATOR, false);
        inOrder.verify(documentMapper).deleteByIds(documentIds);
    }

    /**
     * 断言资产回收步骤未触达任何数据库清退批次（含派生数据与文档批）。
     */
    private void verifyNoDbCleanupTouched() {
        verify(chunkMapper, never()).selectIdsByKbId(any(), anyInt());
        verify(chunkPhysicalDeleteService, never())
                .deleteChunks(anyCollection(), anyString(), anyBoolean());
        verify(documentMapper, never()).selectIdsByKbId(any(), anyInt());
    }
}
