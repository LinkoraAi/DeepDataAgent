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
import static org.mockito.Mockito.when;

/**
 * {@link IngestionStartupMaintenance} 启动恢复单测（实例限定收敛）。
 * <p>覆盖：就绪后按本实例键一次性收敛崩溃残留（有残留 / 无残留两态同一入口）、
 * 收敛异常仅 ERROR 留痕不阻断应用启动、就绪时序严格早于摄入消费线程启动。</p>
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

    @Test
    void should_convergeInstanceResidueOnce_when_convergeCrashResidueOnReady_given_residualRows() {
        // given：本实例崩溃重启后残留 2 篇在飞文档
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE))
                .thenReturn(2);

        // when
        maintenance.convergeCrashResidueOnReady();

        // then：以固定文案按本实例键一次性收敛（不重建队列、不自动重跑）
        verify(inFlightTaskConverger).convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
    }

    @Test
    void should_completeSilently_when_convergeCrashResidueOnReady_given_noResidualRows() {
        // given：正常重启无残留，在飞注册表无未完成文档（收敛数 0，天然幂等）
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE))
                .thenReturn(0);

        // when // then：不抛异常即通过，仍恰好收敛一次
        assertDoesNotThrow(() -> maintenance.convergeCrashResidueOnReady());
        verify(inFlightTaskConverger).convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
    }

    @Test
    void should_notBlockStartup_when_convergeCrashResidueOnReady_given_convergenceThrows() {
        // given：收敛阶段 DB / 注册表异常
        doThrow(new RuntimeException("DB 不可达")).when(inFlightTaskConverger)
                .convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);

        // when // then：异常仅 ERROR 留痕，绝不阻断应用启动
        assertDoesNotThrow(() -> maintenance.convergeCrashResidueOnReady());
        verify(inFlightTaskConverger).convergeAllOfType(InFlightTaskType.DOC_INGESTION, STARTUP_ERROR_MESSAGE);
    }

    @Test
    void should_runBeforeIngestionConsumers_when_annotated_given_readyEvent() throws NoSuchMethodException {
        // given
        Order order = IngestionStartupMaintenance.class
                .getMethod("convergeCrashResidueOnReady").getAnnotation(Order.class);

        // then：启动恢复严格早于摄入消费线程启动，保证「先恢复、后消费」
        assertNotNull(order);
        assertTrue(order.value() < INGESTION_CONSUMER_ORDER, "摄入启动恢复必须早于摄入消费线程启动时序");
    }
}