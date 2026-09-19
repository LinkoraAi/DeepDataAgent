package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Transition} 值对象不变量单测（双列迁移声明的紧凑构造器校验）。
 * <p>重点锁死三件事：① 目标对外状态与目标相位不可同时为空；
 * ② 空前置集合合法且语义为「该维度不拼条件」；③ 前置集合的防御性拷贝与枚举序归一。</p>
 */
class TransitionTest {

    @Test
    void should_throwIllegalArgument_when_constructor_given_bothTargetsNull() {
        // given / when / then：目标 status 与 toPhase 同时为 null 时迁移无任何写入语义
        assertThrows(IllegalArgumentException.class,
                () -> new Transition(null, null, Set.of(), Set.of()));
    }

    @Test
    void should_throwIllegalArgument_when_constructor_given_nullPredecessorSets() {
        // given / when / then：前置集合为空引用非法（任意维度请传空集）
        assertThrows(IllegalArgumentException.class,
                () -> new Transition(AgentSessionStatus.IDLE, TurnPhase.IDLE, null, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Transition(AgentSessionStatus.IDLE, TurnPhase.IDLE, Set.of(), null));
    }

    @Test
    void should_reportNoStatusGuard_when_constructor_given_emptyStatusFrom() {
        // given / when
        Transition phaseOnly = new Transition(null, TurnPhase.CANCELLING,
                Set.of(), Set.of(TurnPhase.RUNNING));

        // then：空前置集合即任意对外状态、不拼条件；本次不改写 status 列
        assertFalse(phaseOnly.guardsStatus(), "空前置集合即无 status 守卫");
        assertTrue(phaseOnly.guardsPhase());
        assertFalse(phaseOnly.touchesStatus());
        assertTrue(phaseOnly.touchesPhase());
    }

    @Test
    void should_reportNoPhaseGuard_when_constructor_given_emptyPhaseFrom() {
        // given / when
        Transition statusOnly = new Transition(AgentSessionStatus.IDLE, TurnPhase.IDLE,
                Set.of(AgentSessionStatus.RUNNING), Set.of());

        // then：空相位前置即任意相位、不拼 turn_phase 条件
        assertTrue(statusOnly.guardsStatus());
        assertFalse(statusOnly.guardsPhase());
        assertTrue(statusOnly.touchesStatus());
    }

    @Test
    void should_copyPredecessorsDefensively_when_constructor_given_mutableSets() {
        // given
        Set<AgentSessionStatus> mutableStatus = new HashSet<>();
        mutableStatus.add(AgentSessionStatus.IDLE);
        Set<TurnPhase> mutablePhase = new HashSet<>();
        mutablePhase.add(TurnPhase.RUNNING);

        // when
        Transition transition = new Transition(AgentSessionStatus.RUNNING, TurnPhase.RUNNING,
                mutableStatus, mutablePhase);
        mutableStatus.add(AgentSessionStatus.TERMINATED);
        mutablePhase.add(TurnPhase.CANCELLING);

        // then：不可变拷贝，外部集合后续变更不影响声明
        assertEquals(Set.of(AgentSessionStatus.IDLE), transition.statusFrom());
        assertEquals(Set.of(TurnPhase.RUNNING), transition.phaseFrom());
        assertThrows(UnsupportedOperationException.class,
                () -> transition.statusFrom().add(AgentSessionStatus.TERMINATED));
        assertThrows(UnsupportedOperationException.class,
                () -> transition.phaseFrom().add(TurnPhase.CANCELLING));
    }

    @Test
    void should_normalizeToEnumOrder_when_constructor_given_unorderedSets() {
        // given / when
        Transition transition = new Transition(AgentSessionStatus.IDLE, TurnPhase.IDLE,
                Set.of(AgentSessionStatus.RESCHEDULING, AgentSessionStatus.RUNNING),
                Set.of(TurnPhase.CANCELLING, TurnPhase.RUNNING));

        // then：迭代顺序为枚举声明顺序（生成的 IN 参数顺序稳定可断言）
        assertEquals(List.of(AgentSessionStatus.RUNNING, AgentSessionStatus.RESCHEDULING),
                List.copyOf(transition.statusFrom()));
        assertEquals(List.of(TurnPhase.RUNNING, TurnPhase.CANCELLING),
                List.copyOf(transition.phaseFrom()));
    }

    @Test
    void should_guardOnlyIdleStatusAndNoPhase_when_beginTurn_given_beginTurnConstant() {
        // given / when
        Transition beginTurn = Transition.BEGIN_TURN;

        // then：非对称守卫——前置仅 status=idle 单值（不看相位），目标双列 idlerunning→running
        assertEquals(AgentSessionStatus.RUNNING, beginTurn.to());
        assertEquals(TurnPhase.RUNNING, beginTurn.toPhase());
        assertEquals(EnumSet.of(AgentSessionStatus.IDLE), beginTurn.statusFrom());
        assertTrue(beginTurn.phaseFrom().isEmpty());
        assertTrue(beginTurn.guardsStatus());
        assertFalse(beginTurn.guardsPhase());
    }

    @Test
    void should_touchOnlyPhase_when_phaseDedicatedTransitions_given_awaitResumeCancel() {
        // given / when / then：HITL 挂起 / 续跑 / 取消三条迁移均不改写对外 status 列
        for (Transition transition : List.of(Transition.PHASE_AWAIT, Transition.PHASE_RESUME,
                Transition.PHASE_CANCEL)) {
            assertFalse(transition.touchesStatus(), transition + " 不应改写 status 列");
            assertTrue(transition.touchesPhase(), transition + " 应改写 turn_phase 列");
        }
    }

    @Test
    void should_finishToIdleWithoutPhaseGuard_when_finishTurn_given_anyPhase() {
        // given / when
        Transition finish = Transition.FINISH_TURN;

        // then：相位前置为空集——这是 cancelling 不得成为静默死路的兜底出口
        assertEquals(AgentSessionStatus.IDLE, finish.to());
        assertEquals(TurnPhase.IDLE, finish.toPhase());
        assertEquals(EnumSet.of(AgentSessionStatus.RUNNING), finish.statusFrom());
        assertFalse(finish.guardsPhase());
    }

    @Test
    void should_limitTerminatePhaseToRunning_when_terminate_given_cancellingPhase() {
        // given / when
        Transition terminate = Transition.TERMINATE;

        // then：cancelling 优先于 terminated——相位前置仅 running，cancelling 时不落终态
        assertEquals(AgentSessionStatus.TERMINATED, terminate.to());
        assertEquals(EnumSet.of(TurnPhase.RUNNING), terminate.phaseFrom());
        assertFalse(terminate.phaseFrom().contains(TurnPhase.CANCELLING));
    }

    @Test
    void should_leavePhaseUntouched_when_rescheduleTransitions_given_nullTargetPhase() {
        // given / when / then：重新调度两迁移不改写相位（toPhase 为 null）
        assertFalse(Transition.RESCHEDULE.touchesPhase());
        assertEquals(AgentSessionStatus.RESCHEDULING, Transition.RESCHEDULE.to());
        assertFalse(Transition.RESUME_FROM_RESCHEDULE.touchesPhase());
        assertEquals(AgentSessionStatus.RUNNING, Transition.RESUME_FROM_RESCHEDULE.to());
    }

    @Test
    void should_excludeAwaitingPhase_when_abandonOrphanExecution_given_terminalPhases() {
        // given / when
        Transition abandon = Transition.ABANDON_ORPHAN_EXECUTION;

        // then：仅复位 running / cancelling 孤儿执行相位，awaiting_confirmation 跨重启不触碰
        assertEquals(EnumSet.of(TurnPhase.RUNNING, TurnPhase.CANCELLING), abandon.phaseFrom());
        assertEquals(EnumSet.of(AgentSessionStatus.RUNNING, AgentSessionStatus.RESCHEDULING), abandon.statusFrom());
        assertEquals(AgentSessionStatus.IDLE, abandon.to());
        assertEquals(TurnPhase.IDLE, abandon.toPhase());
    }
}