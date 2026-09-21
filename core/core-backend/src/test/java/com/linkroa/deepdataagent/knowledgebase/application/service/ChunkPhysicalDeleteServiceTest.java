package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceApi;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergencePlan;
import com.linkroa.deepdataagent.knowledgebase.api.DocumentGraphConvergenceResult;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ImportType;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepresentationRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.ChunkRepository;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkPhysicalDeleteService} 单元测试（删除原语，组 6 两段式重排后契约）。
 * <p>覆盖：两段式固定序（来源投影 → 文档投影 → 知识库投影 → <b>事务外 prepare</b> → 单事务
 * 「apply（收敛+回收）→ 删表示 → 删切片行 → 计数回写」→ <b>提交后 reconcile</b>，
 * 切片行删除 MUST 晚于收敛与回收——spec R5「分块行删除晚于重建读取」与「写回事务失败整体回滚、
 * 分块行仍在」的编排层断言）；prepare 失败零写入（未进事务、任何删行 never）；apply 失败上抛且
 * 删行 never；收口异常仅留痕不上抛（删除事实已提交）；空集合与全空元素零交互；入参去重；
 * <b>收敛触发由批次数据推导</b>（全人工批三步契约零触达、kbId 预取零发起）；跨库批按预取映射
 * 逐库 prepare/apply/reconcile 各一次；审计锚点取组内最小文档ID，投影全缺时以 null 锚点照常
 * 收敛（降级不阻断）；重复执行收敛第二次起语句零影响（调用面幂等）。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class ChunkPhysicalDeleteServiceTest {

    /** 测试用知识库ID */
    private static final Long KB_ID = 10L;

    /** 测试用知识库ID（第二分组，跨库批次收敛用例） */
    private static final Long OTHER_KB_ID = 20L;

    /** 测试用文档ID（第一分组，兼收敛审计锚点） */
    private static final Long DOC_ID = 100L;

    /** 测试用文档ID（第二分组，多文档分组计数用例） */
    private static final Long OTHER_DOC_ID = 200L;

    /** 测试用切片ID集合 */
    private static final Long CHUNK_ID_1 = 1001L;
    private static final Long CHUNK_ID_2 = 1002L;
    private static final Long CHUNK_ID_3 = 1003L;

    /** 测试用操作人 */
    private static final String OPERATOR = "tester";

    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private ChunkRepresentationRepository chunkRepresentationRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentGraphConvergenceApi documentGraphConvergenceApi;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private ChunkPhysicalDeleteService service;

    /**
     * 事务模板透传桩（真实执行回调，InOrder 可断言回调体内序）；lenient 兼容零交互短路用例。
     */
    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    /**
     * 构造指定文档（用于计数回写断言）。
     *
     * @param documentId 文档ID
     * @param chunkCount 当前分块计数
     * @return 文档聚合根
     */
    private Document buildDocument(Long documentId, int chunkCount) {
        OffsetDateTime now = OffsetDateTime.now();
        return Document.restore(documentId, KB_ID, "手册.pdf", FileType.PDF,
                DocumentStatus.PROCESSED, null, 1024L, chunkCount, null,
                ImportType.UPLOAD, null, null, null, now, now);
    }

    /**
     * 构造「切片 → 投影值」轻量映射（保持插入顺序，模拟仓储 id 升序返回）。
     *
     * @param chunkIds     切片ID（按序）
     * @param projectedIds 对应投影值（文档ID或知识库ID，按序）
     * @return 有序映射
     */
    private Map<Long, Long> buildProjection(List<Long> chunkIds, List<Long> projectedIds) {
        Map<Long, Long> projection = new LinkedHashMap<>();
        for (int i = 0; i < chunkIds.size(); i++) {
            projection.put(chunkIds.get(i), projectedIds.get(i));
        }
        return projection;
    }

    /**
     * 构造「切片 → 来源标识」投影（保持插入顺序，模拟仓储 id 升序返回）。
     *
     * @param chunkIds 切片ID（按序）
     * @param sources  对应来源标识（按序）
     * @return 有序映射
     */
    private Map<Long, ChunkSource> buildSourceProjection(List<Long> chunkIds, ChunkSource... sources) {
        Map<Long, ChunkSource> projection = new LinkedHashMap<>();
        for (int i = 0; i < chunkIds.size(); i++) {
            projection.put(chunkIds.get(i), sources[i]);
        }
        return projection;
    }

    @Test
    void should_runTwoPhaseFixedOrder_when_deleteChunks_given_countSyncAndParsedSource() {
        // given：两条切片同属一个文档与同一个知识库，计数回写开启且批次含「解析产生」来源（推导为收敛）
        List<Long> ids = List.of(CHUNK_ID_1, CHUNK_ID_2);
        DocumentGraphConvergencePlan plan = mock(DocumentGraphConvergencePlan.class);
        DocumentGraphConvergenceResult result =
                new DocumentGraphConvergenceResult(1, 2, 0, 0, 3, 1, List.of(), List.of());
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.PARSED, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(DOC_ID, DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(KB_ID, KB_ID)));
        when(documentGraphConvergenceApi.prepare(KB_ID, DOC_ID, ids, OPERATOR)).thenReturn(plan);
        when(documentGraphConvergenceApi.apply(plan)).thenReturn(result);
        when(chunkRepository.countByDocumentId(DOC_ID)).thenReturn(0L);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(DOC_ID, 2)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.deleteChunks(ids, OPERATOR, true);

        // then：事务外三段投影（来源 → 文档 → 知识库）与 prepare 均在最前；单事务内固定序
        //       apply（收敛+缓存回收）→ 删表示 → 删切片行 → 计数回写；收口在事务模板之后——
        //       「删切片行晚于收敛与回收」「prepare 在事务外」「收口在提交后」任何违背即失败
        InOrder order = inOrder(chunkRepository, chunkRepresentationRepository, transactionTemplate,
                documentGraphConvergenceApi, documentRepository);
        order.verify(chunkRepository).findSourcesByChunkIds(ids);
        order.verify(chunkRepository).findDocumentIdsByChunkIds(ids);
        order.verify(chunkRepository).findKbIdsByChunkIds(ids);
        order.verify(documentGraphConvergenceApi).prepare(KB_ID, DOC_ID, ids, OPERATOR);
        order.verify(transactionTemplate).execute(any());
        order.verify(documentGraphConvergenceApi).apply(plan);
        order.verify(chunkRepresentationRepository).deleteByChunkIds(ids);
        order.verify(chunkRepository).deleteByIds(ids);
        order.verify(chunkRepository).countByDocumentId(DOC_ID);
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        order.verify(documentRepository).update(captor.capture());
        assertEquals(0, captor.getValue().chunkCount().intValue());
        order.verify(documentGraphConvergenceApi).reconcilePendingVectorContent(plan, result);
    }

    @Test
    void should_doNothing_when_deleteChunks_given_emptyCollection() {
        // when：空集合入参直接返回（计数开关取值不改变短路语义）
        service.deleteChunks(List.of(), OPERATOR, true);

        // then：零 DB 交互、零图谱交互、零事务（来源投影也不发起）
        verifyNoInteractions(chunkRepository, chunkRepresentationRepository, documentRepository,
                documentGraphConvergenceApi, transactionTemplate);
    }

    @Test
    void should_doNothing_when_deleteChunks_given_nullElementsOnly() {
        // when：集合非空但元素全为 null，清洗后为空即返回
        service.deleteChunks(Arrays.asList((Long) null, null), OPERATOR, true);

        // then：零 DB 交互、零图谱交互、零事务
        verifyNoInteractions(chunkRepository, chunkRepresentationRepository, documentRepository,
                documentGraphConvergenceApi, transactionTemplate);
    }

    @Test
    void should_deduplicateIdsAndUseMinDocAnchor_when_deleteChunks_given_duplicatedInput() {
        // given：入参含重复ID，计数回写关闭、批次含解析来源（文档链场景），文档投影乱序多文档
        List<Long> ids = List.of(CHUNK_ID_1, CHUNK_ID_2, CHUNK_ID_1);
        List<Long> deduplicated = List.of(CHUNK_ID_1, CHUNK_ID_2);
        when(chunkRepository.findSourcesByChunkIds(deduplicated))
                .thenReturn(buildSourceProjection(deduplicated, ChunkSource.PARSED, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(deduplicated))
                .thenReturn(buildProjection(deduplicated, List.of(OTHER_DOC_ID, DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(deduplicated))
                .thenReturn(buildProjection(deduplicated, List.of(KB_ID, KB_ID)));
        when(documentGraphConvergenceApi.prepare(KB_ID, DOC_ID, deduplicated, OPERATOR))
                .thenReturn(mock(DocumentGraphConvergencePlan.class));
        when(documentGraphConvergenceApi.apply(any())).thenReturn(DocumentGraphConvergenceResult.empty());

        // when
        service.deleteChunks(ids, OPERATOR, false);

        // then：下游一律收到去重后的集合（保持首现顺序）；锚点取组内最小文档ID（升序稳定可重入）
        InOrder order = inOrder(chunkRepository, chunkRepresentationRepository, documentGraphConvergenceApi);
        order.verify(chunkRepository).findKbIdsByChunkIds(deduplicated);
        order.verify(documentGraphConvergenceApi).prepare(KB_ID, DOC_ID, deduplicated, OPERATOR);
        order.verify(chunkRepresentationRepository).deleteByChunkIds(deduplicated);
        order.verify(chunkRepository).deleteByIds(deduplicated);
    }

    @Test
    void should_skipThreeStepContractWithZeroRemote_when_deleteChunks_given_allManualSourceBatch() {
        // given：人工切片删除场景——计数回写开启、批次全为「人工新增」来源，图谱侧必须零读写
        List<Long> ids = List.of(CHUNK_ID_1, CHUNK_ID_2);
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.MANUAL, ChunkSource.MANUAL));
        when(chunkRepository.findDocumentIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(DOC_ID, DOC_ID)));
        when(chunkRepository.countByDocumentId(DOC_ID)).thenReturn(0L);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(DOC_ID, 2)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.deleteChunks(ids, OPERATOR, true);

        // then：三步契约零触达（prepare/apply/reconcile 均 never）——人工批零远程、零图谱读写；
        //       kbId 预取投影也不发起；删表示 → 删切片行 → 计数回写在同一事务内次序不变
        verifyNoInteractions(documentGraphConvergenceApi);
        verify(chunkRepository, never()).findKbIdsByChunkIds(anyCollection());
        InOrder order = inOrder(transactionTemplate, chunkRepresentationRepository, chunkRepository,
                documentRepository);
        order.verify(transactionTemplate).execute(any());
        order.verify(chunkRepresentationRepository).deleteByChunkIds(ids);
        order.verify(chunkRepository).deleteByIds(ids);
        order.verify(documentRepository).update(any());
    }

    @Test
    void should_prepareApplyReconcilePerKbGroup_when_deleteChunks_given_crossKbParsedBatch() {
        // given：三条切片跨两个知识库且全部为解析来源（跨库批逐库分组三步各一次）
        List<Long> ids = List.of(CHUNK_ID_1, CHUNK_ID_2, CHUNK_ID_3);
        when(chunkRepository.findSourcesByChunkIds(ids)).thenReturn(
                buildSourceProjection(ids, ChunkSource.PARSED, ChunkSource.PARSED, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids)).thenReturn(
                buildProjection(ids, List.of(DOC_ID, DOC_ID, OTHER_DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(KB_ID, OTHER_KB_ID, OTHER_KB_ID)));
        when(documentGraphConvergenceApi.prepare(eq(KB_ID), any(), anyList(), any()))
                .thenReturn(mock(DocumentGraphConvergencePlan.class));
        when(documentGraphConvergenceApi.prepare(eq(OTHER_KB_ID), any(), anyList(), any()))
                .thenReturn(mock(DocumentGraphConvergencePlan.class));
        when(documentGraphConvergenceApi.apply(any())).thenReturn(DocumentGraphConvergenceResult.empty());

        // when
        service.deleteChunks(ids, OPERATOR, false);

        // then：逐库 prepare 各一次（各带本库切片子集与本组最小文档锚点），事务内逐库 apply、
        //       提交后逐库收口——全部删行仍晚于两库收敛
        verify(documentGraphConvergenceApi, times(1)).prepare(KB_ID, DOC_ID, List.of(CHUNK_ID_1), OPERATOR);
        verify(documentGraphConvergenceApi, times(1))
                .prepare(OTHER_KB_ID, DOC_ID, List.of(CHUNK_ID_2, CHUNK_ID_3), OPERATOR);
        verify(documentGraphConvergenceApi, times(2)).apply(any());
        verify(documentGraphConvergenceApi, times(2)).reconcilePendingVectorContent(any(), any());
        verify(chunkRepository, times(1)).deleteByIds(ids);
    }

    @Test
    void should_stillConvergeWithNullAnchor_when_deleteChunks_given_documentProjectionEmpty() {
        // given：批次含解析来源但文档投影为空（并发下投影缺行的极端形态）——锚点为 null 照常收敛
        List<Long> ids = List.of(CHUNK_ID_1);
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids)).thenReturn(Map.of());
        when(chunkRepository.findKbIdsByChunkIds(ids)).thenReturn(buildProjection(ids, List.of(KB_ID)));
        when(documentGraphConvergenceApi.apply(any())).thenReturn(DocumentGraphConvergenceResult.empty());

        // when：不抛异常（删除链不因锚点缺失阻断，重建降级由契约侧处置）
        service.deleteChunks(ids, OPERATOR, false);

        // then
        verify(documentGraphConvergenceApi).prepare(eq(KB_ID), isNull(), eq(ids), eq(OPERATOR));
        verify(chunkRepository).deleteByIds(ids);
    }

    @Test
    void should_keepDeleteStepsAndSkipGraph_when_deleteChunks_given_emptySourceProjection() {
        // given：来源投影为空（并发下切片行已物理消失）——不构成收敛依据，图谱侧零读写
        List<Long> ids = List.of(CHUNK_ID_1);
        when(chunkRepository.findSourcesByChunkIds(ids)).thenReturn(Map.of());

        // when
        service.deleteChunks(ids, OPERATOR, false);

        // then：清退三步照旧（仍在单事务内），kbId 预取与三步契约零触达
        verify(chunkRepresentationRepository).deleteByChunkIds(ids);
        verify(chunkRepository).deleteByIds(ids);
        verify(chunkRepository, never()).findKbIdsByChunkIds(anyCollection());
        verifyNoInteractions(documentGraphConvergenceApi);
    }

    @Test
    void should_stopBeforeAnyWriteAndSkipTransaction_when_deleteChunks_given_prepareFails() {
        // given：事务外重建计算失败（远程故障）——此时 MUST NOT 已开启任何事务、MUST NOT 有任何删行
        List<Long> ids = List.of(CHUNK_ID_1);
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(KB_ID)));
        when(documentGraphConvergenceApi.prepare(KB_ID, DOC_ID, ids, OPERATOR))
                .thenThrow(new IllegalStateException("重建远程调用失败"));

        // when & then：异常上抛；事务未开、表示与切片行未删（零写入残留，重试从投影重新开始）
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deleteChunks(ids, OPERATOR, false));
        assertEquals("重建远程调用失败", error.getMessage());
        verifyNoInteractions(transactionTemplate, chunkRepresentationRepository);
        verify(chunkRepository, never()).deleteByIds(anyList());
        verify(documentGraphConvergenceApi, never()).apply(any());
        verify(documentGraphConvergenceApi, never()).reconcilePendingVectorContent(any(), any());
    }

    @Test
    void should_keepChunkRowsIntact_when_deleteChunks_given_applyFailsInsideTransaction() {
        // given：收敛写回（apply）失败——事务回调内上抛，整体回滚由事务模板承担
        List<Long> ids = List.of(CHUNK_ID_1);
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(KB_ID)));
        when(documentGraphConvergenceApi.prepare(KB_ID, DOC_ID, ids, OPERATOR))
                .thenReturn(mock(DocumentGraphConvergencePlan.class));
        when(documentGraphConvergenceApi.apply(any())).thenThrow(new IllegalStateException("图谱收敛原语故障"));

        // when & then：异常传播；apply 排在删行之前——切片行与表示 MUST NOT 被删除（可重推），
        //               计数回写与提交后收口零触达
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deleteChunks(ids, OPERATOR, false));
        assertEquals("图谱收敛原语故障", error.getMessage());
        verify(chunkRepository, never()).deleteByIds(anyList());
        verify(chunkRepresentationRepository, never()).deleteByChunkIds(anyList());
        verify(documentGraphConvergenceApi, never()).reconcilePendingVectorContent(any(), any());
        verify(chunkRepository, never()).countByDocumentId(any());
    }

    @Test
    void should_propagateErrorAndSkipReconcile_when_deleteChunks_given_chunkRowDeleteFails() {
        // given：删切片行失败（收敛已成功）——同事务整体回滚，提交后收口 MUST NOT 触达
        List<Long> ids = List.of(CHUNK_ID_1);
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(KB_ID)));
        when(documentGraphConvergenceApi.prepare(KB_ID, DOC_ID, ids, OPERATOR))
                .thenReturn(mock(DocumentGraphConvergencePlan.class));
        when(documentGraphConvergenceApi.apply(any())).thenReturn(DocumentGraphConvergenceResult.empty());
        when(chunkRepository.deleteByIds(ids)).thenThrow(new IllegalStateException("切片行删除故障"));

        // when & then
        assertThrows(IllegalStateException.class, () -> service.deleteChunks(ids, OPERATOR, false));
        verify(documentGraphConvergenceApi, never()).reconcilePendingVectorContent(any(), any());
    }

    @Test
    void should_swallowReconcileFailureAfterCommit_when_deleteChunks_given_reconcileThrows() {
        // given：删除事务已提交后收口抛异常——MUST NOT 反噬已提交删除事实（不上抛）
        List<Long> ids = List.of(CHUNK_ID_1);
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(KB_ID)));
        when(documentGraphConvergenceApi.prepare(KB_ID, DOC_ID, ids, OPERATOR))
                .thenReturn(mock(DocumentGraphConvergencePlan.class));
        when(documentGraphConvergenceApi.apply(any())).thenReturn(DocumentGraphConvergenceResult.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("收口远程故障"))
                .when(documentGraphConvergenceApi).reconcilePendingVectorContent(any(), any());

        // when & then：不抛；删除已全部完成
        assertDoesNotThrow(() -> service.deleteChunks(ids, OPERATOR, false));
        verify(chunkRepository).deleteByIds(ids);
    }

    @Test
    void should_replaySameCallsWithZeroEffect_when_deleteChunks_given_repeatedExecution() {
        // given：同一批重复执行两次（幂等重入）——第二次起契约调用面同式重放、零额外删行语义
        List<Long> ids = List.of(CHUNK_ID_1);
        DocumentGraphConvergencePlan plan = mock(DocumentGraphConvergencePlan.class);
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(KB_ID)));
        when(documentGraphConvergenceApi.prepare(KB_ID, DOC_ID, ids, OPERATOR)).thenReturn(plan);
        when(documentGraphConvergenceApi.apply(plan)).thenReturn(
                DocumentGraphConvergenceResult.empty(), DocumentGraphConvergenceResult.empty());

        // when
        service.deleteChunks(ids, OPERATOR, false);
        service.deleteChunks(ids, OPERATOR, false);

        // then：三步契约各重放两次、入参完全一致（受影响集合自账本重推，不依赖句柄）
        verify(documentGraphConvergenceApi, times(2)).prepare(KB_ID, DOC_ID, ids, OPERATOR);
        verify(documentGraphConvergenceApi, times(2)).apply(plan);
        verify(documentGraphConvergenceApi, times(2)).reconcilePendingVectorContent(plan,
                DocumentGraphConvergenceResult.empty());
    }

    @Test
    void should_groupCountSyncByDocument_when_deleteChunks_given_multipleDocuments() {
        // given：三条切片分属两个文档（1001→DOC、1002/1003→OTHER_DOC），全为人工来源
        List<Long> ids = List.of(CHUNK_ID_1, CHUNK_ID_2, CHUNK_ID_3);
        when(chunkRepository.findSourcesByChunkIds(ids)).thenReturn(
                buildSourceProjection(ids, ChunkSource.MANUAL, ChunkSource.MANUAL, ChunkSource.MANUAL));
        when(chunkRepository.findDocumentIdsByChunkIds(ids)).thenReturn(
                buildProjection(ids, List.of(DOC_ID, OTHER_DOC_ID, OTHER_DOC_ID)));
        when(chunkRepository.countByDocumentId(DOC_ID)).thenReturn(3L);
        when(chunkRepository.countByDocumentId(OTHER_DOC_ID)).thenReturn(7L);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(buildDocument(DOC_ID, 4)));
        when(documentRepository.findById(OTHER_DOC_ID)).thenReturn(Optional.of(buildDocument(OTHER_DOC_ID, 9)));
        when(documentRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.deleteChunks(ids, OPERATOR, true);

        // then：每篇文档各一次 COUNT + UPDATE，计数以存活切片数回写；本批全人工来源，kbId 预取零触达
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, times(2)).update(captor.capture());
        Map<Long, Integer> countByDocument = new LinkedHashMap<>();
        captor.getAllValues().forEach(document -> countByDocument.put(document.id(), document.chunkCount()));
        assertEquals(3, countByDocument.get(DOC_ID).intValue());
        assertEquals(7, countByDocument.get(OTHER_DOC_ID).intValue());
        verify(chunkRepository, never()).findKbIdsByChunkIds(anyCollection());
    }

    @Test
    void should_skipCountSyncForMissingDocument_when_deleteChunks_given_documentAlreadyRemoved() {
        // given：并发终删——回写时文档行已不存在，本批清退不得中断（批次含解析来源）
        List<Long> ids = List.of(CHUNK_ID_1);
        when(chunkRepository.findSourcesByChunkIds(ids))
                .thenReturn(buildSourceProjection(ids, ChunkSource.PARSED));
        when(chunkRepository.findDocumentIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(DOC_ID)));
        when(chunkRepository.findKbIdsByChunkIds(ids))
                .thenReturn(buildProjection(ids, List.of(KB_ID)));
        when(documentGraphConvergenceApi.prepare(KB_ID, DOC_ID, ids, OPERATOR))
                .thenReturn(mock(DocumentGraphConvergencePlan.class));
        when(documentGraphConvergenceApi.apply(any())).thenReturn(DocumentGraphConvergenceResult.empty());
        when(chunkRepository.countByDocumentId(DOC_ID)).thenReturn(0L);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        // when：不抛异常
        service.deleteChunks(ids, OPERATOR, true);

        // then：切片清退完整生效（收敛带 kbId），缺失文档不回写
        verify(chunkRepository).deleteByIds(ids);
        verify(documentGraphConvergenceApi).prepare(KB_ID, DOC_ID, ids, OPERATOR);
        verify(documentRepository, never()).update(any());
    }
}
