package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskRegistry;
import com.linkroa.deepdataagent.knowledgebase.api.InFlightTaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在飞任务登记的事务同步器（受理事务内登记，未提交时补偿移除）。
 *
 * <p>登记与受理必须在同一事务边界内原子生效，而缓存注册表不参与数据库事务。本组件把登记挂到
 * 当前事务生命周期：{@link TransactionSynchronization#beforeCommit(boolean) beforeCommit} 时逐条登记，
 * 任一条失败即抛出任务登记异常使事务回滚；{@link TransactionSynchronization#afterCompletion(int)
 * afterCompletion} 在事务未提交时移除本次已尝试登记的成员。</p>
 *
 * <p>每次 {@link #register} 调用注册一个独立的同步器，因此同一事务内的多次登记（如覆盖换版：
 * 旧文档清退 + 新文档摄入）各自拥有补偿责任，部分失败时已登记的成员会被逐个移除。集合登记与
 * 移除均幂等，重试安全。</p>
 *
 * <p>无事务上下文时退化为立即登记（防御分支）：此时没有可回滚的事务边界，但仍应保留任务凭据，
 * 由启动恢复按业务状态一致性校验兜底。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class InFlightTaskTransactionalRegistrar {

    private static final Logger log = LoggerFactory.getLogger(InFlightTaskTransactionalRegistrar.class);

    /** 在飞任务注册表（跨 BC 契约，实现位于 rag BC） */
    private final InFlightTaskRegistry inFlightTaskRegistry;

    /**
     * 构造在飞任务事务同步器。
     *
     * @param inFlightTaskRegistry 在飞任务注册表
     */
    public InFlightTaskTransactionalRegistrar(InFlightTaskRegistry inFlightTaskRegistry) {
        this.inFlightTaskRegistry = inFlightTaskRegistry;
    }

    /**
     * 在当前受理事务内登记在飞任务。
     * <p>必须在受理事务体（事务同步已激活）内调用；登记在提交前执行，失败即触发事务回滚。</p>
     *
     * @param taskType 任务类型，必填
     * @param taskId   任务主体主键，必填
     * @throws com.linkroa.deepdataagent.shared.exception.TaskRegistrationException 登记失败（事务将回滚）
     */
    public void register(InFlightTaskType taskType, Long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 防御分支：无事务同步上下文时不具备补偿能力，直接登记并交由启动恢复兜底
            log.warn("在飞任务登记缺少事务上下文，按立即登记处理: taskType={}, taskId={}", taskType, taskId);
            inFlightTaskRegistry.register(taskType, taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new RegistrationSynchronization(taskType, taskId));
    }

    /**
     * 单条登记的事务同步器：提交前登记，未提交时补偿移除。
     */
    private final class RegistrationSynchronization implements TransactionSynchronization {

        /** 任务类型 */
        private final InFlightTaskType taskType;

        /** 任务主体主键 */
        private final Long taskId;

        /** 是否已发起过登记（补偿依据；登记结果不确定时也按已发起处理，移除为无害空操作） */
        private boolean registerAttempted;

        /**
         * 构造单条登记同步器。
         *
         * @param taskType 任务类型
         * @param taskId   任务主体主键
         */
        private RegistrationSynchronization(InFlightTaskType taskType, Long taskId) {
            this.taskType = taskType;
            this.taskId = taskId;
        }

        /**
         * 提交前登记：失败直接上抛，使受理事务回滚。
         *
         * @param readOnly 是否为只读事务
         */
        @Override
        public void beforeCommit(boolean readOnly) {
            // 先置位再登记：即使登记响应不确定（如超时），回滚时也尝试移除，避免残留
            registerAttempted = true;
            inFlightTaskRegistry.register(taskType, taskId);
        }

        /**
         * 事务结束：未提交时补偿移除已尝试登记的成员，失败仅留痕（启动恢复会再次收敛）。
         *
         * @param status 事务完成状态
         */
        @Override
        public void afterCompletion(int status) {
            if (!registerAttempted || status == TransactionSynchronization.STATUS_COMMITTED) {
                return;
            }
            try {
                inFlightTaskRegistry.unregister(taskType, taskId);
            } catch (RuntimeException e) {
                log.error("受理事务未提交，补偿移除在飞成员失败（残留由下次启动收敛）: taskType={}, taskId={}",
                        taskType, taskId, e);
            }
        }
    }
}