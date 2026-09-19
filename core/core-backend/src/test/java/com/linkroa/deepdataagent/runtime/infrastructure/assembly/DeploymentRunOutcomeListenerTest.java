package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.DeploymentRunLifecycleApi;
import com.linkroa.deepdataagent.runtime.domain.event.TurnFinished;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link DeploymentRunOutcomeListener} 提交后回写监听器单测（fix-runtime-layering 5.4）：
 * 打标事件回写正确 outcome、非调度触发忽略、契约抛错吞咽不穿透；
 * 并以注解结构断言锁定「仅提交后触发（AFTER_COMMIT）、回滚不发」的时序语义。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentRunOutcomeListenerTest {

    @Mock
    private DeploymentRunLifecycleApi deploymentRunLifecycleApi;

    private DeploymentRunOutcomeListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeploymentRunOutcomeListener();
        ReflectionTestUtils.setField(listener, "deploymentRunLifecycleApi", deploymentRunLifecycleApi);
    }

    @Test
    void should_writeBackCorrectOutcome_when_onTurnFinished_given_scheduledTerminalEvent() {
        // given（调度打标会话的终局事件）
        TurnFinished event = new TurnFinished("sess_1", "cron", "succeeded");

        // when
        listener.onTurnFinished(event);

        // then：按事件 sessionId + outcome 回写
        verify(deploymentRunLifecycleApi).completeByTriggerSession("sess_1", "succeeded");
    }

    @Test
    void should_ignoreEvent_when_onTurnFinished_given_blankTriggerType() {
        // given（非调度触发：无打标 / 空白打标两态）
        TurnFinished nullTrigger = new TurnFinished("sess_1", null, "failed");
        TurnFinished blankTrigger = new TurnFinished("sess_1", "  ", "terminated");

        // when
        listener.onTurnFinished(nullTrigger);
        listener.onTurnFinished(blankTrigger);

        // then：门槛与原 writeBackDeploymentRun 一致，零回写
        verifyNoInteractions(deploymentRunLifecycleApi);
    }

    @Test
    void should_swallowErrorWithoutPropagation_when_onTurnFinished_given_apiThrows() {
        // given（agent BC 侧故障）
        TurnFinished event = new TurnFinished("sess_1", "cron", "terminated");
        doThrow(new RuntimeException("agent BC 不可用"))
                .when(deploymentRunLifecycleApi).completeByTriggerSession("sess_1", "terminated");

        // when / then：失败仅告警不抛出——不影响已提交的会话终态主链路（对齐现状语义）
        assertDoesNotThrow(() -> listener.onTurnFinished(event));
    }

    @Test
    void should_bindToAfterCommitPhase_when_onTurnFinished_given_listenerMethodInspected() {
        // given（结构锚点：AFTER_COMMIT 由 Spring 保证「回滚不发」，改相位即破坏 D4）
        Method method = assertDoesNotThrow(() -> DeploymentRunOutcomeListener.class
                .getMethod("onTurnFinished", TurnFinished.class));

        // when
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        // then
        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }
}
