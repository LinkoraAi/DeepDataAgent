package com.linkroa.deepdataagent.rag.application.task;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CleanupStartupMaintenance} 删除清退启动恢复单测（实例限定收敛）。
 * <p>覆盖：就绪后依次收敛文档清退与整库清退两类本实例残留（文案口径与删除链既有留痕一致）、
 * 无残留时零额外交互（能力退化换取多实例下不自伤，MUST NOT 自动重触发续跑）、
 * 收敛异常仅 ERROR 留痕不阻断应用启动、就绪时序严格晚于摄入消费线程启动。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class CleanupStartupMaintenanceTest {

    /** 文档清退启动恢复留痕（须与生产常量口径一致） */
    private static final String DOC_CLEANUP_RECOVERY_TRACE = "[DELETE-FAILED] step=startup_recovery";

    /** 整库清退启动恢复留痕（须与生产常量口径一致） */
    private static final String KB_CLEANUP_RECOVERY_TRACE =
            "[KB-CLEANUP] step=STARTUP_RECOVERY: 服务重启中断，请重新执行删除";

    /** 摄入消费线程启动的就绪时序（既有约定 @Order(200)） */
    private static final int INGESTION_CONSUMER_ORDER = 200;

    /** 在飞任务收敛器 Mock（按本实例键读取残留并条件置态） */
    @Mock
    private InFlightTaskConverger inFlightTaskConverger;

    /** 被测启动恢复器 */
    @InjectMocks
    private CleanupStartupMaintenance maintenance;

    @Test
    void should_convergeDocThenKbCleanupResidue_when_onReady_given_instanceResidue() {
        // given：本实例两类清退均有残留（文档 2 条 / 知识库 1 条）
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_CLEANUP, DOC_CLEANUP_RECOVERY_TRACE))
                .thenReturn(2);
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.KB_CLEANUP, KB_CLEANUP_RECOVERY_TRACE))
                .thenReturn(1);

        // when
        maintenance.retriggerDeletingKnowledgeBasesOnReady();

        // then：文档清退先收敛、整库清退后收敛，各自携带与删除链一致的留痕文案
        InOrder order = inOrder(inFlightTaskConverger);
        order.verify(inFlightTaskConverger)
                .convergeAllOfType(InFlightTaskType.DOC_CLEANUP, DOC_CLEANUP_RECOVERY_TRACE);
        order.verify(inFlightTaskConverger)
                .convergeAllOfType(InFlightTaskType.KB_CLEANUP, KB_CLEANUP_RECOVERY_TRACE);
    }

    @Test
    void should_convergeWithoutRetrigger_when_onReady_given_noInstanceResidue() {
        // given：本实例无残留，两类收敛均返回 0
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.DOC_CLEANUP, DOC_CLEANUP_RECOVERY_TRACE))
                .thenReturn(0);
        when(inFlightTaskConverger.convergeAllOfType(InFlightTaskType.KB_CLEANUP, KB_CLEANUP_RECOVERY_TRACE))
                .thenReturn(0);

        // when
        maintenance.retriggerDeletingKnowledgeBasesOnReady();

        // then：仅收敛读取，零重触发提交（无残留处置权归用户重删）
        verify(inFlightTaskConverger)
                .convergeAllOfType(InFlightTaskType.DOC_CLEANUP, DOC_CLEANUP_RECOVERY_TRACE);
        verify(inFlightTaskConverger)
                .convergeAllOfType(InFlightTaskType.KB_CLEANUP, KB_CLEANUP_RECOVERY_TRACE);
        verifyNoMoreInteractions(inFlightTaskConverger);
    }

    @Test
    void should_notBlockStartupAndSkipKbResidue_when_onReady_given_docConvergenceThrows() {
        // given：文档清退残留收敛阶段异常
        doThrow(new RuntimeException("DB 异常")).when(inFlightTaskConverger)
                .convergeAllOfType(InFlightTaskType.DOC_CLEANUP, DOC_CLEANUP_RECOVERY_TRACE);

        // when // then：异常仅 ERROR 留痕，不阻断应用启动；整库清退收敛不再执行
        assertDoesNotThrow(() -> maintenance.retriggerDeletingKnowledgeBasesOnReady());
        verify(inFlightTaskConverger, never())
                .convergeAllOfType(InFlightTaskType.KB_CLEANUP, KB_CLEANUP_RECOVERY_TRACE);
    }

    @Test
    void should_runAfterIngestionConsumers_when_annotated_given_readyEvent() throws NoSuchMethodException {
        // given
        Order order = CleanupStartupMaintenance.class
                .getMethod("retriggerDeletingKnowledgeBasesOnReady").getAnnotation(Order.class);

        // then：严格晚于摄入消费线程启动，保证恢复动作不抢先于摄入链路就位
        assertNotNull(order);
        assertTrue(order.value() > INGESTION_CONSUMER_ORDER,
                "清退启动恢复必须晚于摄入消费线程启动时序");
    }
}