package com.linkroa.deepdataagent.runtime.infrastructure.client;

import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link HarnessBuiltAgent} 委托语义单测（upgrade-agentscope-203 D7）。
 * <p>核心断言：定向中断透传到 2.0.3 的 {@code interrupt(userId, sessionId)} 槽位重载，
 * MUST NOT 触碰废弃无参 {@code interrupt()}（其打在 defaultSessionId 幽灵槽位、升级后静默失效）。</p>
 */
class HarnessBuiltAgentTest {

    @Test
    void should_delegateTargetedInterrupt_when_interrupt_given_sessionIdentity() {
        // given
        HarnessAgent harness = mock(HarnessAgent.class);
        HarnessBuiltAgent agent = new HarnessBuiltAgent(harness);

        // when
        agent.interrupt("u-1", "sess_abc");

        // then：透传到定向重载；废弃无参 API 零调用
        verify(harness).interrupt("u-1", "sess_abc");
        verify(harness, never()).interrupt();
    }

    @Test
    void should_delegateClose_when_close_given_wrappedHandle() {
        // given
        HarnessAgent harness = mock(HarnessAgent.class);
        HarnessBuiltAgent agent = new HarnessBuiltAgent(harness);

        // when
        agent.close();

        // then
        verify(harness).close();
    }
}
