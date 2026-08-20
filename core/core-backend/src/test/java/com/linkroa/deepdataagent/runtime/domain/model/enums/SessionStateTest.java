package com.linkroa.deepdataagent.runtime.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@link SessionState} 内部状态机单测：合法 / 非法转换、瞬态终态回落 IDLE、
 * 落库投影（{@code SessionState → RoundStatus}）与 {@link AgentSessionStatus} 不承载瞬态值。
 */
class SessionStateTest {

    @Test
    void should_allowIdleToRunning_when_canTransitionTo_given_idle() {
        // when & then（发起一轮：IDLE 仅允许迁往 RUNNING）
        assertTrue(SessionState.IDLE.canTransitionTo(SessionState.RUNNING));
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.DONE));
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.INTERRUPTED));
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.ERROR));
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.IDLE));
    }

    @Test
    void should_allowRunningToTerminal_when_canTransitionTo_given_running() {
        // when & then（RUNNING 仅允许迁往三类瞬态终态）
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.DONE));
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.INTERRUPTED));
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.ERROR));
        assertFalse(SessionState.RUNNING.canTransitionTo(SessionState.IDLE));
        assertFalse(SessionState.RUNNING.canTransitionTo(SessionState.RUNNING));
    }

    @Test
    void should_allowTerminalToIdle_when_canTransitionTo_given_terminal() {
        // when & then（三类瞬态终态一轮结束均仅回落 IDLE）
        for (SessionState terminal : new SessionState[]{SessionState.DONE, SessionState.INTERRUPTED, SessionState.ERROR}) {
            assertTrue(terminal.canTransitionTo(SessionState.IDLE), terminal + " 应允许回落 IDLE");
            assertFalse(terminal.canTransitionTo(SessionState.RUNNING), terminal + " 不应允许重新 RUNNING");
        }
    }

    @Test
    void should_throwOnIllegalTransition_when_validateTransition_given_idleToDone() {
        // when & then（非法转换必须报错）
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SessionState.IDLE.validateTransition(SessionState.DONE));
        assertTrue(ex.getMessage().contains("IDLE") && ex.getMessage().contains("DONE"));
    }

    @Test
    void should_projectRoundStatus_when_toRoundStatus_given_terminal() {
        // when & then（SessionState → RoundStatus 落库投影）
        assertEquals(RoundStatus.COMPLETED, SessionState.DONE.toRoundStatus());
        assertEquals(RoundStatus.FAILED, SessionState.INTERRUPTED.toRoundStatus());
        assertEquals(RoundStatus.FAILED, SessionState.ERROR.toRoundStatus());
    }

    @Test
    void should_throwOnProjection_when_toRoundStatus_given_nonTerminal() {
        // when & then（非终态无落库投影，调用属编程错误）
        assertThrows(IllegalStateException.class, SessionState.IDLE::toRoundStatus);
        assertThrows(IllegalStateException.class, SessionState.RUNNING::toRoundStatus);
    }

    @Test
    void should_notCarryTransientTerminal_when_agentSessionStatusValues_given_orthogonal() {
        // when & then（会话级持久化/对外状态与内部瞬态终态正交，不扩展 DONE/INTERRUPTED/ERROR）
        assertArrayEquals(
                new AgentSessionStatus[]{AgentSessionStatus.IDLE, AgentSessionStatus.RUNNING, AgentSessionStatus.TERMINATED},
                AgentSessionStatus.values());
    }
}