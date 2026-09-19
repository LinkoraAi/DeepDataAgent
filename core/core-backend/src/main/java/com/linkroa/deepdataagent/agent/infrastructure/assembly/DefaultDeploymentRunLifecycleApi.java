package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.DeploymentRunLifecycleApi;
import com.linkroa.deepdataagent.agent.application.service.DeploymentApplicationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 调度运行终态回写服务契约进程内实现（{@link DeploymentRunLifecycleApi}）。
 * <p>委托 {@link DeploymentApplicationService#completeRunByTriggerSession} 在单事务内完成
 * CAS 终态化与调度器 {@code last_status} 快照刷新（写事务边界按仓库规范置于应用服务）；
 * 幂等与 outcome 校验语义由该用例承载，本实现仅做契约适配，runtime 消费方不触碰 agent 领域对象。</p>
 */
@Service
public class DefaultDeploymentRunLifecycleApi implements DeploymentRunLifecycleApi {

    @Resource
    private DeploymentApplicationService deploymentApplicationService;

    @Override
    public void completeByTriggerSession(String sessionId, String outcome) {
        deploymentApplicationService.completeRunByTriggerSession(sessionId, outcome);
    }
}
