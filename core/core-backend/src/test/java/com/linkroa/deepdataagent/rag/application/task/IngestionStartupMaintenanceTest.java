package com.linkroa.deepdataagent.rag.application.task;

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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link IngestionStartupMaintenance} 启动恢复单测。
 * <p>恢复范围严格限定为本实例在飞注册表键：只按本实例标识读取成员并逐个做数据库状态一致性校验，
 * 不再存在全库非终态扫描；并验证异常不阻断启动、就绪时序早于消费线程。</p>
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

    /** 被测启动恢复器 */
    @InjectMocks
    private IngestionStartupMaintenance maintenance;

    /**
     * 场景：本实例注册表有残留。
     * 预期：按本实例键收敛，且不再触发任何全库扫描能力。
     */
    @Test
    void should_convergeOwnInstanceResidue_when_convergeCrashResidueOnReady_given_registryResidue() {
        // given
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE))
                .thenReturn(2);

        // when
        maintenance.convergeCrashResidueOnReady();

        // then
        verify(inFlightTaskConverger).convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
        verifyNoMoreInteractions(inFlightTaskConverger);
    }

    /**
     * 场景：本实例注册表无残留。
     * 预期：幂等空转，不抛异常。
     */
    @Test
    void should_completeSilently_when_convergeCrashResidueOnReady_given_noResidue() {
        // given
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE))
                .thenReturn(0);

        // when / then
        assertDoesNotThrow(() -> maintenance.convergeCrashResidueOnReady());
        verify(inFlightTaskConverger).convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
    }

    /**
     * 场景：本实例注册表读取或收敛抛异常（如缓存不可用）。
     * 预期：异常仅留痕、不阻断启动。
     */
    @Test
    void should_notBlockStartup_when_convergeCrashResidueOnReady_given_registryThrows() {
        // given
        doThrow(new RuntimeException("注册表不可用")).when(inFlightTaskConverger)
                .convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);

        // when / then
        assertDoesNotThrow(() -> maintenance.convergeCrashResidueOnReady());
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