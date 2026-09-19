package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionStateMachine} 双列迁移矩阵单测（唯一事实源锁死）。
 * <p>邻接表由 {@link Transition} 命名常量派生；本测试独立重述十条迁移的入边聚合与
 * 两维守卫判定，覆盖对外状态四态与内部相位四态的组合。</p>
 */
class SessionStateMachineTest {

    @Test
    void should_declareTenTransitions_when_transitions_given_stateMachineTable() {
        // given
        List<Transition> expected = List.of(
                Transition.BEGIN_TURN,
                Transition.PHASE_AWAIT,
                Transition.PHASE_RESUME,
                Transition.PHASE_CANCEL,
                Transition.ABANDON_WAITING_CONFIRMATION,
                Transition.FINISH_TURN,
                Transition.TERMINATE,
                Transition.RESCHEDULE,
                Transition.RESUME_FROM_RESCHEDULE,
                Transition.ABANDON_ORPHAN_EXECUTION);

        // when
        List<Transition> declared = SessionStateMachine.transitions();

        // then：迁移表与 Transition 命名常量一一对应，无多余、无遗漏（归档已移出）
        assertEquals(expected, declared, "迁移表与命名常量集合不一致");
    }

    @Test
    void should_aggregateIncomingByStatus_when_incomingTransitions_given_runningTarget() {
        // given / when
        List<Transition> incoming = SessionStateMachine.incomingTransitions(AgentSessionStatus.RUNNING);

        // then：running 的入边 = BEGIN_TURN + RESUME_FROM_RESCHEDULE（仅统计改写 status 列的迁移）
        assertEquals(List.of(Transition.BEGIN_TURN, Transition.RESUME_FROM_RESCHEDULE), incoming);
    }

    @Test
    void should_aggregateIncomingByStatus_when_incomingTransitions_given_idleTarget() {
        // given / when
        List<Transition> incoming = SessionStateMachine.incomingTransitions(AgentSessionStatus.IDLE);

        // then：idle 的入边 = ABANDON_WAITING_CONFIRMATION + FINISH_TURN + ABANDON_ORPHAN_EXECUTION
        assertEquals(List.of(Transition.ABANDON_WAITING_CONFIRMATION, Transition.FINISH_TURN,
                Transition.ABANDON_ORPHAN_EXECUTION), incoming);
    }

    @Test
    void should_returnEmptyIncoming_when_incomingTransitions_given_phaseOnlyTargets() {
        // given / when / then：相位专用迁移不改写 status 列，故不进入入边；各目标态仅收 status 改写迁移
        assertEquals(List.of(Transition.TERMINATE),
                SessionStateMachine.incomingTransitions(AgentSessionStatus.TERMINATED));
        assertEquals(List.of(Transition.RESCHEDULE),
                SessionStateMachine.incomingTransitions(AgentSessionStatus.RESCHEDULING));
    }

    @Test
    void should_throwIllegalArgument_when_incomingTransitions_given_nullTarget() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> SessionStateMachine.incomingTransitions(null));
    }

    @Test
    void should_throwIllegalArgument_when_isLegal_given_nullTransition() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> SessionStateMachine.isLegal(AgentSessionStatus.IDLE, TurnPhase.IDLE, null));
    }

    @Test
    void should_matchTwoDimensions_when_isLegal_given_statusAndPhaseGuardedTransition() {
        // given / when / then：BEGIN_TURN 仅 status=idle 命中（相位维度不设限）
        assertTrue(SessionStateMachine.isLegal(AgentSessionStatus.IDLE, TurnPhase.IDLE, Transition.BEGIN_TURN));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.IDLE, Transition.BEGIN_TURN));
    }

    @Test
    void should_relyOnPhaseGuardOnly_when_isLegal_given_phaseOnlyTransition() {
        // given / when / then：PHASE_CANCEL 不看 status，仅 phase∈{running, awaiting_confirmation}
        assertTrue(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.RUNNING, Transition.PHASE_CANCEL));
        assertTrue(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.AWAITING_CONFIRMATION,
                Transition.PHASE_CANCEL));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.IDLE, Transition.PHASE_CANCEL));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.CANCELLING,
                Transition.PHASE_CANCEL));
    }

    @Test
    void should_acceptAnyPhase_when_isLegal_given_emptyPhaseFrom() {
        // given / when / then：FINISH_TURN 相位前置为空集——cancelling 亦能命中（不得为静默死路）
        for (TurnPhase phase : TurnPhase.values()) {
            assertTrue(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, phase, Transition.FINISH_TURN),
                    "FINISH_TURN 应对任意相位合法: " + phase);
        }
    }

    @Test
    void should_hitAwaitingConfirmationOnly_when_isLegal_given_abandonWaitingConfirmation() {
        // given / when / then：HITL 等待期作废仅命中 awaiting_confirmation——
        // running 相位的活跃执行 MUST NOT 被本迁移静默落 idle（须走 PHASE_CANCEL 取消链收流）
        assertTrue(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.AWAITING_CONFIRMATION,
                Transition.ABANDON_WAITING_CONFIRMATION));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.RUNNING,
                Transition.ABANDON_WAITING_CONFIRMATION));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.CANCELLING,
                Transition.ABANDON_WAITING_CONFIRMATION));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.IDLE, TurnPhase.IDLE,
                Transition.ABANDON_WAITING_CONFIRMATION));
    }

    @Test
    void should_excludeAwaitingPhase_when_isLegal_given_abandonOrphanExecution() {
        // given / when / then：启动恢复仅复位 running / cancelling 孤儿执行，awaiting 保留
        assertTrue(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.RUNNING,
                Transition.ABANDON_ORPHAN_EXECUTION));
        assertTrue(SessionStateMachine.isLegal(AgentSessionStatus.RESCHEDULING, TurnPhase.CANCELLING,
                Transition.ABANDON_ORPHAN_EXECUTION));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, TurnPhase.AWAITING_CONFIRMATION,
                Transition.ABANDON_ORPHAN_EXECUTION));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.IDLE, TurnPhase.IDLE,
                Transition.ABANDON_ORPHAN_EXECUTION));
    }

    @ParameterizedTest
    @EnumSource(AgentSessionStatus.class)
    void should_rejectNullStatus_when_isLegal_given_statusGuardedTransition(AgentSessionStatus ignored) {
        // given / when / then：带状态守卫的迁移遇 null 当前状态一律不命中
        assertFalse(SessionStateMachine.isLegal(null, TurnPhase.IDLE, Transition.BEGIN_TURN));
        assertFalse(SessionStateMachine.isLegal(null, TurnPhase.RUNNING, Transition.FINISH_TURN));
    }

    @ParameterizedTest
    @EnumSource(TurnPhase.class)
    void should_rejectNullPhase_when_isLegal_given_phaseGuardedTransition(TurnPhase ignored) {
        // given / when / then：带相位守卫的迁移遇 null 当前相位一律不命中
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, null, Transition.PHASE_CANCEL));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.RUNNING, null, Transition.TERMINATE));
    }

    @Test
    void should_reportReachableStatuses_when_reachableFrom_given_idleIdle() {
        // given / when：idle/idle 仅可开跑一轮
        Collection<AgentSessionStatus> reachable = SessionStateMachine.reachableFrom(
                AgentSessionStatus.IDLE, TurnPhase.IDLE);

        // then
        assertEquals(List.of(AgentSessionStatus.RUNNING), List.copyOf(reachable));
    }

    @Test
    void should_reportReachableStatuses_when_reachableFrom_given_runningRunning() {
        // given / when
        Collection<AgentSessionStatus> reachable = SessionStateMachine.reachableFrom(
                AgentSessionStatus.RUNNING, TurnPhase.RUNNING);

        // then：可收敛 idle / 终止 terminated / 重新调度（相位专用迁移不改写 status，不计入）
        assertTrue(reachable.contains(AgentSessionStatus.IDLE));
        assertTrue(reachable.contains(AgentSessionStatus.TERMINATED));
        assertTrue(reachable.contains(AgentSessionStatus.RESCHEDULING));
        assertFalse(reachable.contains(AgentSessionStatus.RUNNING));
    }

    @Test
    void should_skipPhaseGuardedTransitions_when_reachableFrom_given_nullPhase() {
        // given / when / then：相位为 null 时带守卫迁移不命中，但空集守卫迁移仍可命中
        Collection<AgentSessionStatus> reachable = SessionStateMachine.reachableFrom(AgentSessionStatus.RUNNING, null);
        assertTrue(reachable.contains(AgentSessionStatus.IDLE));
        assertFalse(reachable.contains(AgentSessionStatus.TERMINATED));
    }

    @Test
    void should_returnImmutableViews_when_transitions_given_sameCallTwice() {
        // given / when
        List<Transition> first = SessionStateMachine.transitions();
        List<Transition> second = SessionStateMachine.transitions();

        // then：同一不可变视图，防止调用方篡改邻接表
        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class,
                () -> first.add(Transition.BEGIN_TURN),
                "迁移表必须不可变");
    }

    @Test
    void should_returnImmutableIncoming_when_incomingTransitions_given_sameCallTwice() {
        // given / when
        List<Transition> first = SessionStateMachine.incomingTransitions(AgentSessionStatus.IDLE);
        List<Transition> second = SessionStateMachine.incomingTransitions(AgentSessionStatus.IDLE);

        // then
        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class,
                () -> second.add(Transition.BEGIN_TURN));
    }

    @Test
    void should_exposeSameSetViaAllTransitions_when_allTransitions_given_repeatedCall() {
        // given / when
        Collection<Transition> all = SessionStateMachine.allTransitions();

        // then：与 transitions() 同序等长
        assertEquals(SessionStateMachine.transitions(), List.copyOf(all));
        assertTrue(Set.copyOf(all).size() == SessionStateMachine.transitions().size());
    }
}