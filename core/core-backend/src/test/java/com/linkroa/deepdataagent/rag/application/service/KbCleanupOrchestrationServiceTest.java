package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.knowledgebase.api.KbCacheCleanupApi;
import com.linkroa.deepdataagent.knowledgebase.api.KbCleanupWriter;
import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link KbCleanupOrchestrationService} 整库清退编排单测。
 * <p>覆盖：「Storage 先删、DB 后删」步骤序（资产 → 派生数据 → 文档 → 图谱四表 → 缓存 → 收口）、
 * 分批循环至 0、「零重试」——任一关键步失败即时 {@code markFailed} 留痕并终止本任务
 * （失败步骤只执行一次，MUST NOT 重试退避）、非关键缓存步骤失败不阻断收口、
 * 收口条件物理 DELETE（{@code executeDelete}）0 行命中幂等空转、空入参无操作、方法永不抛出。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class KbCleanupOrchestrationServiceTest {

    /** 测试知识库主键 */
    private static final Long KB_ID = 21L;

    /** 生效的清退批次上限（测试内显式设定，避免隐式共享默认值） */
    private static final int BATCH_SIZE = 500;

    @Mock
    private KbCleanupWriter kbCleanupWriter;

    @Mock
    private EntityNodeGraphRepository entityNodeGraphRepository;

    @Mock
    private RelationEdgeGraphRepository relationEdgeGraphRepository;

    @Mock
    private EntityInfoVectorRepository entityInfoVectorRepository;

    @Mock
    private RelationInfoVectorRepository relationInfoVectorRepository;

    @Mock
    private KbCacheCleanupApi kbCacheCleanupApi;

    @Mock
    private KnowledgeBaseApi knowledgeBaseApi;

    @Mock
    private InFlightTaskRegistry inFlightTaskRegistry;

    @InjectMocks
    private KbCleanupOrchestrationService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "batchSize", BATCH_SIZE);
    }

    @Test
    void should_runStepsInOrder_when_cleanup_given_allStepsSucceed() {
        // given：派生数据两批、文档两批后返回 0（分批循环至取批为空的收敛点）
        when(kbCleanupWriter.cleanupDerivedDataBatch(KB_ID, BATCH_SIZE)).thenReturn(5, 0);
        when(kbCleanupWriter.cleanupDocumentsBatch(KB_ID, BATCH_SIZE)).thenReturn(2, 0);
        when(knowledgeBaseApi.executeDelete(KB_ID)).thenReturn(true);

        // when
        service.cleanup(KB_ID);

        // then：Storage 资产回收恒先于任何数据库清退批次，图谱按「图行 → 向量行」固定序，最后收口
        InOrder inOrder = inOrder(kbCleanupWriter, entityNodeGraphRepository, relationEdgeGraphRepository,
                entityInfoVectorRepository, relationInfoVectorRepository, kbCacheCleanupApi, knowledgeBaseApi);
        inOrder.verify(kbCleanupWriter).cleanupAssetsByKnowledgeBase(KB_ID);
        inOrder.verify(kbCleanupWriter, times(2)).cleanupDerivedDataBatch(KB_ID, BATCH_SIZE);
        inOrder.verify(kbCleanupWriter, times(2)).cleanupDocumentsBatch(KB_ID, BATCH_SIZE);
        inOrder.verify(entityNodeGraphRepository).deleteByKbId(KB_ID);
        inOrder.verify(relationEdgeGraphRepository).deleteByKbId(KB_ID);
        inOrder.verify(entityInfoVectorRepository).deleteByKbId(KB_ID);
        inOrder.verify(relationInfoVectorRepository).deleteByKbId(KB_ID);
        inOrder.verify(kbCacheCleanupApi).deleteCachesByKnowledgeBase(KB_ID);
        inOrder.verify(knowledgeBaseApi).executeDelete(KB_ID);
        verify(knowledgeBaseApi, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_markFailedAndTerminateOnce_when_cleanup_given_assetStepFails() {
        // given：Storage 首步失败（关键步骤）
        doThrow(new RuntimeException("对象存储不可达")).when(kbCleanupWriter).cleanupAssetsByKnowledgeBase(KB_ID);

        // when
        service.cleanup(KB_ID);

        // then：零重试——该步仅执行一次；即时留痕含步骤标识；后续任何 DB 清退步骤与收口均不触达
        verify(kbCleanupWriter, times(1)).cleanupAssetsByKnowledgeBase(KB_ID);
        verify(knowledgeBaseApi).markFailed(KB_ID, "[KB-CLEANUP] step=ASSETS: 对象存储不可达");
        verifyNoDbStepsTouched();
        verify(knowledgeBaseApi, never()).executeDelete(any());
    }

    @Test
    void should_markFailedAndTerminateOnce_when_cleanup_given_derivedDataStepFails() {
        // given：资产已回收，派生数据批清退抛错（已成功批次不回滚）
        doThrow(new RuntimeException("切片删除原语事务失败"))
                .when(kbCleanupWriter).cleanupDerivedDataBatch(KB_ID, BATCH_SIZE);

        // when
        service.cleanup(KB_ID);

        // then：仅一次尝试即终止，文档 / 图谱 / 缓存 / 收口全部不触达，留痕归因到 DERIVED_DATA
        verify(kbCleanupWriter, times(1)).cleanupDerivedDataBatch(KB_ID, BATCH_SIZE);
        verify(kbCleanupWriter, never()).cleanupDocumentsBatch(anyLong(), anyInt());
        verifyNoInteractions(entityNodeGraphRepository, relationEdgeGraphRepository,
                entityInfoVectorRepository, relationInfoVectorRepository, kbCacheCleanupApi);
        verify(knowledgeBaseApi).markFailed(eq(KB_ID), contains("step=DERIVED_DATA"));
        verify(knowledgeBaseApi, never()).executeDelete(any());
    }

    @Test
    void should_markFailedOnceAndSkipRemainingTables_when_cleanup_given_graphStepFails() {
        // given：DB 批清退已完成，图谱首表抛错（specs「关键步骤失败即时留痕待重删」场景）
        when(kbCleanupWriter.cleanupDerivedDataBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(kbCleanupWriter.cleanupDocumentsBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(entityNodeGraphRepository.deleteByKbId(KB_ID)).thenThrow(new RuntimeException("数据库异常"));

        // when
        service.cleanup(KB_ID);

        // then：图谱步骤零重试，后续图表 / 缓存 / 收口不触达（库置 DELETE_FAILED 待用户重删续跑）
        verify(entityNodeGraphRepository, times(1)).deleteByKbId(KB_ID);
        verifyNoInteractions(relationEdgeGraphRepository, entityInfoVectorRepository,
                relationInfoVectorRepository, kbCacheCleanupApi);
        verify(knowledgeBaseApi).markFailed(eq(KB_ID), contains("step=GRAPH"));
        verify(knowledgeBaseApi, never()).executeDelete(any());
    }

    @Test
    void should_stillConvergeWithoutFailedState_when_cleanup_given_cacheStepFails() {
        // given：非关键缓存步骤失败（可再生派生物，不阻断收口）
        when(kbCleanupWriter.cleanupDerivedDataBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(kbCleanupWriter.cleanupDocumentsBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        doThrow(new RuntimeException("缓存表写入失败")).when(kbCacheCleanupApi).deleteCachesByKnowledgeBase(KB_ID);

        // when
        service.cleanup(KB_ID);

        // then：吞异常留痕后继续收口，且不落失败态、缓存步骤不重试
        verify(kbCacheCleanupApi, times(1)).deleteCachesByKnowledgeBase(KB_ID);
        verify(knowledgeBaseApi).executeDelete(KB_ID);
        verify(knowledgeBaseApi, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_treatFinalDeleteAsIdempotentNoop_when_cleanup_given_executeDeleteMissesRow() {
        // given：收口条件 DELETE 零行命中（行已不存在＝已收口，重删链幂等重放）
        when(kbCleanupWriter.cleanupDerivedDataBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(kbCleanupWriter.cleanupDocumentsBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(knowledgeBaseApi.executeDelete(KB_ID)).thenReturn(false);

        // when
        service.cleanup(KB_ID);

        // then：0 行命中不视为失败——仅一次收口调用、零失败留痕
        verify(knowledgeBaseApi, times(1)).executeDelete(KB_ID);
        verify(knowledgeBaseApi, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void should_markFailedOnce_when_cleanup_given_finalDeleteStepThrows() {
        // given：收口回调自身抛错（关键步骤）
        when(kbCleanupWriter.cleanupDerivedDataBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(kbCleanupWriter.cleanupDocumentsBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(knowledgeBaseApi.executeDelete(KB_ID)).thenThrow(new RuntimeException("知识库契约异常"));

        // when
        service.cleanup(KB_ID);

        // then：收口零重试，即时留痕归因 FINAL_DELETE
        verify(knowledgeBaseApi, times(1)).executeDelete(KB_ID);
        verify(knowledgeBaseApi).markFailed(eq(KB_ID), contains("step=FINAL_DELETE"));
    }

    @Test
    void should_neverThrow_when_cleanup_given_markFailedCallbackAlsoFails() {
        // given：留痕回调也异常（数据库整体不可用）
        doThrow(new RuntimeException("对象存储不可达")).when(kbCleanupWriter).cleanupAssetsByKnowledgeBase(KB_ID);
        doThrow(new RuntimeException("数据库不可用")).when(knowledgeBaseApi).markFailed(anyLong(), anyString());

        // when // then：方法永不抛出——保证执行器的在飞登记与并发配额一定释放，库停留 DELETING 交启动恢复
        assertDoesNotThrow(() -> service.cleanup(KB_ID));
        verify(knowledgeBaseApi, times(1)).markFailed(eq(KB_ID), anyString());
    }

    @Test
    void should_doNothing_when_cleanup_given_nullKnowledgeBaseId() {
        // when
        service.cleanup(null);

        // then：空入参按无操作处理，不触达任何契约，MUST NOT 移除在飞成员
        verifyNoInteractions(kbCleanupWriter, entityNodeGraphRepository, relationEdgeGraphRepository,
                entityInfoVectorRepository, relationInfoVectorRepository, kbCacheCleanupApi, knowledgeBaseApi,
                inFlightTaskRegistry);
    }

    // ==================== 在飞成员移除（状态已收敛） ====================

    @Test
    void should_removeMember_when_cleanup_given_allCriticalStepsSucceeded() {
        // given：全链关键步骤成功，收口条件 DELETE 命中知识库行
        when(kbCleanupWriter.cleanupDerivedDataBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(kbCleanupWriter.cleanupDocumentsBatch(KB_ID, BATCH_SIZE)).thenReturn(0);
        when(knowledgeBaseApi.executeDelete(KB_ID)).thenReturn(true);

        // when
        service.cleanup(KB_ID);

        // then：收口即状态已收敛 → 移除本实例的整库清退在飞成员
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.KB_CLEANUP, KB_ID);
    }

    @Test
    void should_removeMember_when_cleanup_given_criticalStepFailedAndTraceWritten() {
        // given：首步资产回收失败，失败留痕回调正常返回（状态已收敛为 DELETE_FAILED）
        doThrow(new RuntimeException("对象存储不可达")).when(kbCleanupWriter).cleanupAssetsByKnowledgeBase(KB_ID);

        // when
        service.cleanup(KB_ID);

        // then：留痕已写入（含未命中的幂等空转）→ 移除在飞成员，库停留 DELETE_FAILED 交用户重删续跑
        verify(knowledgeBaseApi).markFailed(KB_ID, "[KB-CLEANUP] step=ASSETS: 对象存储不可达");
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.KB_CLEANUP, KB_ID);
    }

    @Test
    void should_keepMember_when_cleanup_given_criticalStepFailedAndTraceWriteThrows() {
        // given：资产回收失败且失败留痕回调自身异常（状态未收敛）
        doThrow(new RuntimeException("对象存储不可达")).when(kbCleanupWriter).cleanupAssetsByKnowledgeBase(KB_ID);
        doThrow(new RuntimeException("数据库不可用")).when(knowledgeBaseApi).markFailed(anyLong(), anyString());

        // when
        service.cleanup(KB_ID);

        // then：状态未收敛 → MUST NOT 移除在飞成员，残留凭据交下次启动收敛
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
    }

    /**
     * 断言数据库清退各步骤（派生数据 / 文档 / 图谱 / 缓存）均未被触达。
     */
    private void verifyNoDbStepsTouched() {
        verify(kbCleanupWriter, never()).cleanupDerivedDataBatch(anyLong(), anyInt());
        verify(kbCleanupWriter, never()).cleanupDocumentsBatch(anyLong(), anyInt());
        verifyNoInteractions(entityNodeGraphRepository, relationEdgeGraphRepository,
                entityInfoVectorRepository, relationInfoVectorRepository, kbCacheCleanupApi);
    }
}
