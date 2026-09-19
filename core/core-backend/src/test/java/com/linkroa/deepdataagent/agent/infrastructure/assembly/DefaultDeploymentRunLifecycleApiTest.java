package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.application.service.DeploymentApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;

/**
 * {@link DefaultDeploymentRunLifecycleApi} 单测：契约实现为薄委托，
 * 参数原样透传应用服务用例（幂等 / outcome 校验语义在用例侧覆盖）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultDeploymentRunLifecycleApiTest {

    @Mock private DeploymentApplicationService deploymentApplicationService;

    private DefaultDeploymentRunLifecycleApi api;

    @BeforeEach
    void setUp() {
        api = new DefaultDeploymentRunLifecycleApi();
        ReflectionTestUtils.setField(api, "deploymentApplicationService", deploymentApplicationService);
    }

    @Test
    void should_delegateWithPristineArgs_when_completeByTriggerSession_given_sessionAndOutcome() {
        // given // when
        api.completeByTriggerSession("sess-1", "succeeded");

        // then：契约参数原样透传，不做任何加工
        verify(deploymentApplicationService).completeRunByTriggerSession("sess-1", "succeeded");
    }
}
