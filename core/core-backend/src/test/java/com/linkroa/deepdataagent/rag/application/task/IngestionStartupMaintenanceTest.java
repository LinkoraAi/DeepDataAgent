package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.ChunkBatchWriter;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IngestionStartupMaintenance} 启动恢复单测。
 * <p>覆盖两条互不依赖的收敛路径：注册表快速路径（按本实例键的一致性校验收敛）与
 * 数据库兜底路径（无条件把 {@code PENDING/PROCESSING} 全部置 {@code FAILED}，
 * 不依赖注册表凭据、不设开关）；并验证任一路异常均不阻断启动、就绪时序早于消费线程。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class IngestionStartupMaintenanceTest {

    /** 启动恢复固定文案（与生产常量口径一致） */
    private static final String STARTUP_ERROR_MESSAGE = "[STARTUP] 服务重启中断，请重新解析";

    /** 摄入消费线程启动的就绪时序（既有约定 @Order(200)） */
    private static final int INGESTION_CONSUMER_ORDER = 200;

    /** 在飞任务收敛器 Mock（按本实例键读取残留并条件置态） */
    @Mock
    private InFlightTaskConverger inFlightTaskConverger;

    /** 切片批写契约 Mock（非终态批量收敛原语） */
    @Mock
    private ChunkBatchWriter chunkBatchWriter;

    /** 被测启动恢复器 */
    @InjectMocks
    private IngestionStartupMaintenance maintenance;

    /**
     * 场景：本实例注册表有残留，且库内仍存在非终态文档。
     * 预期：两路都执行——注册表收敛 + 数据库兜底扫尾均以固定文案触发。
     */
    @Test
    void should_runBothPaths_when_convergeCrashResidueOnReady_given_registryAndDatabaseResidue() {
        // given
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE))
                .thenReturn(2);
        when(chunkBatchWriter.failNonTerminal(STARTUP_ERROR_MESSAGE)).thenReturn(14);

        // when
        maintenance.convergeCrashResidueOnReady();

        // then
        verify(inFlightTaskConverger).convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
        verify(chunkBatchWriter).failNonTerminal(STARTUP_ERROR_MESSAGE);
    }

    /**
     * 场景（本次缺陷回归）：注册表键已被清空（收敛 0 篇），但库内仍有 14 篇非终态文档。
     * 预期：数据库兜底路径仍无条件执行——启动恢复不得依赖注册表凭据是否存活。
     */
    @Test
    void should_stillSweepDatabase_when_convergeCrashResidueOnReady_given_registryEmpty() {
        // given
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE))
                .thenReturn(0);
        when(chunkBatchWriter.failNonTerminal(STARTUP_ERROR_MESSAGE)).thenReturn(14);

        // when
        maintenance.convergeCrashResidueOnReady();

        // then
        verify(chunkBatchWriter).failNonTerminal(STARTUP_ERROR_MESSAGE);
    }

    /**
     * 场景：注册表路径抛异常（如缓存不可用）。
     * 预期：数据库兜底仍执行，整体不抛异常、不阻断启动。
     */
    @Test
    void should_stillSweepDatabase_when_convergeCrashResidueOnReady_given_registryThrows() {
        // given
        doThrow(new RuntimeException("注册表不可用")).when(inFlightTaskConverger)
                .convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);

        // when / then
        assertDoesNotThrow(() -> maintenance.convergeCrashResidueOnReady());
        verify(chunkBatchWriter).failNonTerminal(STARTUP_ERROR_MESSAGE);
    }

    /**
     * 场景：数据库兜底路径抛异常（如数据库暂不可达）。
     * 预期：异常仅留痕、不阻断启动；注册表路径已正常执行。
     */
    @Test
    void should_notBlockStartup_when_convergeCrashResidueOnReady_given_databaseSweepThrows() {
        // given
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE))
                .thenReturn(1);
        doThrow(new RuntimeException("DB 不可达")).when(chunkBatchWriter)
                .failNonTerminal(STARTUP_ERROR_MESSAGE);

        // when / then
        assertDoesNotThrow(() -> maintenance.convergeCrashResidueOnReady());
        verify(inFlightTaskConverger).convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
        verify(chunkBatchWriter).failNonTerminal(STARTUP_ERROR_MESSAGE);
    }

    /**
     * 场景：正常重启且无任何残留。
     * 预期：两路均执行且天然幂等（无命中 0 行），不抛异常。
     */
    @Test
    void should_completeSilently_when_convergeCrashResidueOnReady_given_noResidue() {
        // given
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE))
                .thenReturn(0);
        when(chunkBatchWriter.failNonTerminal(STARTUP_ERROR_MESSAGE)).thenReturn(0);

        // when / then
        assertDoesNotThrow(() -> maintenance.convergeCrashResidueOnReady());
        verify(chunkBatchWriter).failNonTerminal(STARTUP_ERROR_MESSAGE);
    }

    /**
     * 场景：就绪事件处理方法的时序注解。
     * 预期：严格早于摄入消费线程启动（@Order(200)），保证「先恢复、后消费」。
     */
    @Test
    void should_runBeforeIngestionConsumers_when_annotated_given_readyEvent() throws NoSuchMethodException {
        // given
        Order order = IngestionStartupMaintenance.class
                .getMethod("convergeCrashResidueOnReady").getAnnotation(Order.class);

        // then
        assertNotNull(order);
        assertTrue(order.value() < INGESTION_CONSUMER_ORDER, "摄入启动恢复必须早于摄入消费线程启动时序");
    }
}
