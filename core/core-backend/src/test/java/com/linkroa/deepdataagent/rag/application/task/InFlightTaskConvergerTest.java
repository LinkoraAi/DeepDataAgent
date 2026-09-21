package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskConvergenceApi;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InFlightTaskConverger} 在飞任务收敛器单测（启动恢复与停机收敛共用的收敛原语）。
 * <p>覆盖：三类任务类型的契约方法分派与成员移除、不一致分支（零状态写入）仍移除成员、
 * 置态抛异常时保留成员且不调用移除、参数非法零交互、按类型批量收敛的计数口径、
 * 空残留零开销跳过，以及停机清空注册表键的静默失败语义。</p>
 *
 * <p>全部外部依赖（跨 BC 注册表契约与收敛契约）均为 Mock，测试离线可重复。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class InFlightTaskConvergerTest {

    /** 测试任务主体主键 */
    private static final Long TASK_ID = 42L;

    /** 另一测试任务主体主键 */
    private static final Long OTHER_TASK_ID = 43L;

    /** 第三个测试任务主体主键 */
    private static final Long THIRD_TASK_ID = 44L;

    /** 收敛时写入的失败原因 / 留痕（与停机收敛文案口径一致） */
    private static final String FAIL_MESSAGE = "[SHUTDOWN] 服务停机中断，请重新解析";

    /** 在飞任务注册表 Mock */
    @Mock
    private InFlightTaskRegistry inFlightTaskRegistry;

    /** 状态一致性校验与条件置态契约 Mock */
    @Mock
    private InFlightTaskConvergenceApi inFlightTaskConvergenceApi;

    /** 被测收敛器 */
    @InjectMocks
    private InFlightTaskConverger converger;

    @Test
    void should_placeTargetStateAndRemoveMember_when_converge_given_consistentState() {
        // given：三类任务各自状态一致，置态成功
        when(inFlightTaskConvergenceApi.convergeIngestion(TASK_ID, FAIL_MESSAGE)).thenReturn(true);
        when(inFlightTaskConvergenceApi.convergeDocumentCleanup(TASK_ID, FAIL_MESSAGE)).thenReturn(true);
        when(inFlightTaskConvergenceApi.convergeKbCleanup(TASK_ID, FAIL_MESSAGE)).thenReturn(true);

        // when
        boolean ingestionConverged = converger.converge(InFlightTaskType.DOC_INGESTION, TASK_ID, FAIL_MESSAGE);
        boolean documentCleanupConverged = converger.converge(InFlightTaskType.DOC_CLEANUP, TASK_ID, FAIL_MESSAGE);
        boolean kbCleanupConverged = converger.converge(InFlightTaskType.KB_CLEANUP, TASK_ID, FAIL_MESSAGE);

        // then：按类型分派到各自的契约方法，置态成功即移除对应成员
        assertTrue(ingestionConverged);
        assertTrue(documentCleanupConverged);
        assertTrue(kbCleanupConverged);
        verify(inFlightTaskConvergenceApi).convergeIngestion(TASK_ID, FAIL_MESSAGE);
        verify(inFlightTaskConvergenceApi).convergeDocumentCleanup(TASK_ID, FAIL_MESSAGE);
        verify(inFlightTaskConvergenceApi).convergeKbCleanup(TASK_ID, FAIL_MESSAGE);
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, TASK_ID);
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_CLEANUP, TASK_ID);
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.KB_CLEANUP, TASK_ID);
    }

    @Test
    void should_keepMemberWithoutError_when_converge_given_inconsistentState() {
        // given：数据库已是终态 / 已被并发推进 / 行不存在，契约零写操作返回 false
        when(inFlightTaskConvergenceApi.convergeIngestion(TASK_ID, FAIL_MESSAGE)).thenReturn(false);

        // when
        boolean converged = converger.converge(InFlightTaskType.DOC_INGESTION, TASK_ID, FAIL_MESSAGE);

        // then：不一致分支不改动数据库状态（无写操作、无异常），成员仍可移除——残留凭据已无意义
        assertFalse(converged);
        verify(inFlightTaskConvergenceApi).convergeIngestion(TASK_ID, FAIL_MESSAGE);
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, TASK_ID);
    }

    @Test
    void should_keepMemberAndLogError_when_converge_given_placeStateThrows() {
        // given：置态写入失败（契约抛 RuntimeException）
        when(inFlightTaskConvergenceApi.convergeDocumentCleanup(TASK_ID, FAIL_MESSAGE))
                .thenThrow(new RuntimeException("DB 不可达"));

        // when
        boolean converged = converger.converge(InFlightTaskType.DOC_CLEANUP, TASK_ID, FAIL_MESSAGE);

        // then：成员必须保留（交由下次启动重试），MUST NOT 触发任何成员移除
        assertFalse(converged);
        verify(inFlightTaskRegistry, never()).unregister(any(), anyLong());
    }

    @Test
    void should_returnFalseWithoutInteraction_when_converge_given_nullTaskTypeOrTaskId() {
        // when：任务类型 / 任务主体主键非法
        boolean nullTaskType = converger.converge(null, TASK_ID, FAIL_MESSAGE);
        boolean nullTaskId = converger.converge(InFlightTaskType.DOC_INGESTION, null, FAIL_MESSAGE);

        // then：直接返回 false 且零交互（不触碰数据库、不触碰注册表）
        assertFalse(nullTaskType);
        assertFalse(nullTaskId);
        verifyNoInteractions(inFlightTaskRegistry, inFlightTaskConvergenceApi);
    }

    @Test
    void should_convergeAllResidue_when_convergeAllOfType_given_inFlightMembers() {
        // given：注册表残留 3 个文档摄入成员，其中 2 个可一致置态
        when(inFlightTaskRegistry.findInFlightTaskIds(InFlightTaskType.DOC_INGESTION))
                .thenReturn(Set.of(TASK_ID, OTHER_TASK_ID, THIRD_TASK_ID));
        when(inFlightTaskConvergenceApi.convergeIngestion(TASK_ID, FAIL_MESSAGE)).thenReturn(true);
        when(inFlightTaskConvergenceApi.convergeIngestion(OTHER_TASK_ID, FAIL_MESSAGE)).thenReturn(false);
        when(inFlightTaskConvergenceApi.convergeIngestion(THIRD_TASK_ID, FAIL_MESSAGE)).thenReturn(true);

        // when
        int convergedCount = converger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, FAIL_MESSAGE);

        // then：返回置态成功数（不一致分支不计入），但逐个成员均被移除
        assertEquals(2, convergedCount);
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, TASK_ID);
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, OTHER_TASK_ID);
        verify(inFlightTaskRegistry).unregister(InFlightTaskType.DOC_INGESTION, THIRD_TASK_ID);
    }

    @Test
    void should_returnZeroWithoutConverge_when_convergeAllOfType_given_noResidue() {
        // given：本实例该类任务无残留（空集合）
        when(inFlightTaskRegistry.findInFlightTaskIds(InFlightTaskType.KB_CLEANUP)).thenReturn(Set.of());

        // when
        int convergedCount = converger.convergeAllOfType(InFlightTaskType.KB_CLEANUP, FAIL_MESSAGE);

        // then：空残留零开销跳过，零置态与零成员移除交互
        assertEquals(0, convergedCount);
        verifyNoInteractions(inFlightTaskConvergenceApi);
        verify(inFlightTaskRegistry, never()).unregister(any(), anyLong());
    }

    @Test
    void should_delegateClear_when_clearInstanceRegistryQuietly_given_healthyRegistry() {
        // when
        converger.clearInstanceRegistryQuietly();

        // then：停机首个动作委托注册表清空本实例全部键
        verify(inFlightTaskRegistry).clearInstanceRegistry();
    }

    @Test
    void should_swallowException_when_clearInstanceRegistryQuietly_given_registryFailure() {
        // given：注册表不可用（清空失败）
        doThrow(new RuntimeException("注册表不可用")).when(inFlightTaskRegistry).clearInstanceRegistry();

        // when // then：仅 ERROR 留痕、不上抛，MUST NOT 阻断停机链的后续等待与收敛
        assertDoesNotThrow(() -> converger.clearInstanceRegistryQuietly());
        verify(inFlightTaskRegistry).clearInstanceRegistry();
    }
}