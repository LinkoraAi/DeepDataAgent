package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import com.linkroa.deepdataagent.shared.exception.TaskRegistrationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link InFlightTaskTransactionalRegistrar} 单测。
 * <p>验证登记与受理事务生命周期的绑定：提交前登记、登记失败上抛使事务回滚、
 * 未提交时补偿移除本事务已尝试登记的全部成员；无事务上下文时退化为立即登记。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class InFlightTaskTransactionalRegistrarTest {

    /** 被测任务类型 */
    private static final InFlightTaskType TASK_TYPE = InFlightTaskType.DOC_INGESTION;

    /** 被测任务ID */
    private static final Long TASK_ID = 100L;

    /** 第二个任务ID（多任务补偿场景） */
    private static final Long SECOND_TASK_ID = 200L;

    /** 在飞任务注册表 Mock */
    @Mock
    private InFlightTaskRegistry inFlightTaskRegistry;

    /** 被测事务同步登记器 */
    @InjectMocks
    private InFlightTaskTransactionalRegistrar registrar;

    /**
     * 清理线程事务同步状态，避免用例间串扰。
     */
    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /**
     * 场景：存在活跃事务同步。
     * 预期：调用时只注册同步器，提交前才真正登记；提交成功后不做补偿移除。
     */
    @Test
    void should_deferRegistrationToBeforeCommit_when_register_given_activeTransaction() {
        // given
        TransactionSynchronizationManager.initSynchronization();

        // when
        registrar.register(TASK_TYPE, TASK_ID);

        // then：登记尚未发生
        verifyNoInteractions(inFlightTaskRegistry);

        // when
        synchronizations().forEach(synchronization -> synchronization.beforeCommit(true));

        // then：提交前完成登记
        verify(inFlightTaskRegistry).register(TASK_TYPE, TASK_ID);

        // when：事务提交成功
        synchronizations().forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        // then：不触发补偿
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
    }

    /**
     * 场景：登记已发生但事务最终回滚。
     * 预期：补偿移除本事务登记的成员。
     */
    @Test
    void should_compensateUnregister_when_afterCompletion_given_rolledBack() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        registrar.register(TASK_TYPE, TASK_ID);
        synchronizations().forEach(synchronization -> synchronization.beforeCommit(true));

        // when
        synchronizations().forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // then
        verify(inFlightTaskRegistry).unregister(TASK_TYPE, TASK_ID);
    }

    /**
     * 场景：同一事务登记多个任务，部分登记成功、部分失败。
     * 预期：已尝试登记的成员在回滚时全部被补偿移除。
     */
    @Test
    void should_compensateAllAttemptedMembers_when_beforeCommit_given_partialFailure() {
        // given：第一条登记成功，第二条抛登记异常
        TransactionSynchronizationManager.initSynchronization();
        doNothing().doThrow(new TaskRegistrationException("操作失败，请稍后重试",
                        new RuntimeException("缓存不可用")))
                .when(inFlightTaskRegistry).register(any(), any());
        registrar.register(TASK_TYPE, TASK_ID);
        registrar.register(TASK_TYPE, SECOND_TASK_ID);

        // when
        List<TransactionSynchronization> synchronizations = synchronizations();
        synchronizations.get(0).beforeCommit(true);
        assertThrows(TaskRegistrationException.class, () -> synchronizations.get(1).beforeCommit(true));

        // then：回滚补偿覆盖全部已尝试成员
        synchronizations.forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(inFlightTaskRegistry).unregister(TASK_TYPE, TASK_ID);
        verify(inFlightTaskRegistry).unregister(TASK_TYPE, SECOND_TASK_ID);
    }

    /**
     * 场景：无事务同步上下文（防御分支）。
     * 预期：立即登记，且不做补偿。
     */
    @Test
    void should_registerImmediately_when_register_given_noTransaction() {
        // given
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        // when
        assertDoesNotThrow(() -> registrar.register(TASK_TYPE, TASK_ID));

        // then
        verify(inFlightTaskRegistry).register(TASK_TYPE, TASK_ID);
        verify(inFlightTaskRegistry, never()).unregister(any(), any());
    }

    /**
     * 场景：提交前登记失败。
     * 预期：任务登记异常上抛，由事务回滚。
     */
    @Test
    void should_propagateFailure_when_beforeCommit_given_registryThrows() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new TaskRegistrationException("操作失败，请稍后重试", new RuntimeException("缓存不可用")))
                .when(inFlightTaskRegistry).register(TASK_TYPE, TASK_ID);
        registrar.register(TASK_TYPE, TASK_ID);

        // when / then
        assertThrows(TaskRegistrationException.class,
                () -> synchronizations().get(0).beforeCommit(true));
    }

    /**
     * 读取当前线程已注册的事务同步器。
     *
     * @return 同步器列表（按注册顺序）
     */
    private static List<TransactionSynchronization> synchronizations() {
        return TransactionSynchronizationManager.getSynchronizations();
    }
}