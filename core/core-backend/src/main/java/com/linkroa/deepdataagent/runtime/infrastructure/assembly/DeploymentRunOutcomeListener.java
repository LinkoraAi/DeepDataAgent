package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.DeploymentRunLifecycleApi;
import com.linkroa.deepdataagent.runtime.domain.event.TurnFinished;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 轮次终局 → 调度运行回写的提交后监听器（fix-runtime-layering 5.2/5.3，design D4）。
 * <p>AFTER_COMMIT 阶段消费 {@link TurnFinished}：终态事务提交成功后才回写，回滚不发——
 * 替代原命令服务对 {@code DeploymentRunLifecycleApi} 的 {@code @Lazy} 直调，
 * 打断 deployment → schedulerApi → 命令服务 → runLifecycleApi 的 Bean 循环边。</p>
 * <p>门槛与容错语义对齐原 {@code writeBackDeploymentRun}：{@code triggerType} 为空
 * （非调度触发）直接忽略；回写失败仅记 ERROR 不抛出——会话终态主链路不受影响，
 * run 悬空由 agent BC 启动回填兜底收敛。监听器运行于事务提交后的调用栈内，
 * MUST NOT 读取同事务未提交数据（回写为跨 BC 独立调用，无需事务上下文）。</p>
 */
@Component
public class DeploymentRunOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRunOutcomeListener.class);

    @Resource
    private DeploymentRunLifecycleApi deploymentRunLifecycleApi;

    /**
     * 事务提交后消费轮次终局事件，回写调度运行终态。
     * <p>非调度触发（{@code triggerType} 为空）直接忽略；回写失败仅记 ERROR 不抛出——会话终态主链路不受影响，
     * run 悬空由 agent BC 启动回填兜底收敛。</p>
     *
     * @param event 轮次终局事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTurnFinished(TurnFinished event) {
        if (StringUtils.isBlank(event.triggerType())) {
            return;
        }
        try {
            deploymentRunLifecycleApi.completeByTriggerSession(event.sessionId(), event.outcome());
        } catch (RuntimeException ex) {
            log.error("调度运行终态回写失败（会话终态不受影响，run 悬空由回填兜底）: sessionId={}, outcome={}",
                    event.sessionId(), event.outcome(), ex);
        }
    }
}
