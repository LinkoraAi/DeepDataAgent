package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.TerminalEventSpec;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.DeploymentOutcome;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalNoOpReason;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalStopReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Decision} 值对象不变量单测（终态决策输出的契约校验）。
 * <p>核心不变量：no-op 与尝试链互斥；尝试链内的事件集必须以指向迁移目标的状态事件收尾，
 * 使「落库事件」与「CAS 实际迁入状态」不可能错位。</p>
 * <p>收场事件集按「线程先、会话后」镜像（{@code session.interrupted} 已废止）。</p>
 */
class DecisionTest {

    /** 合法的 idle 终态事件集（线程状态镜像 + 会话状态，共享 interrupted 原因）。 */
    private static final List<TerminalEventSpec> IDLE_EVENTS = List.of(
            TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED),
            TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED));

    @Test
    void should_markNoOpAndEmptyChain_when_noOp_given_reason() {
        // given / when
        Decision decision = Decision.noOp(TerminalNoOpReason.HITL_SUSPENDED);

        // then
        assertTrue(decision.noOp());
        assertEquals(TerminalNoOpReason.HITL_SUSPENDED, decision.noOpReason());
        assertTrue(decision.attempts().isEmpty());
    }

    @Test
    void should_throwIllegalArgument_when_noOp_given_nullReason() {
        // given / when / then：no-op 必须声明原因，否则与「链全未命中」无法区分
        assertThrows(IllegalArgumentException.class, () -> Decision.noOp(null));
    }

    @Test
    void should_keepPrimaryTargetOfPreferredAttempt_when_primaryTarget_given_chain() {
        // given：首选 terminated、回退 idle 的两段链
        Decision decision = Decision.of(List.of(
                new Decision.Attempt(Transition.TERMINATE,
                        List.of(TerminalEventSpec.threadStatusEvent(AgentSessionStatus.TERMINATED,
                                        TerminalStopReason.MAX_ITERATIONS),
                                TerminalEventSpec.statusEvent(AgentSessionStatus.TERMINATED,
                                        TerminalStopReason.MAX_ITERATIONS)),
                        DeploymentOutcome.FAILED),
                new Decision.Attempt(Transition.FINISH_TURN, IDLE_EVENTS, DeploymentOutcome.TERMINATED)));

        // when / then：primaryTarget 只表示首选，实际采纳由 CAS 链式仲裁（顺序即优先级）
        assertEquals(AgentSessionStatus.TERMINATED, decision.primaryTarget());
        assertEquals(Transition.FINISH_TURN, decision.attempts().get(1).transition());
    }

    @Test
    void should_throwIllegalState_when_primaryTarget_given_noOpDecision() {
        // given
        Decision decision = Decision.noOp(TerminalNoOpReason.LEASE_LOST);

        // when / then
        assertThrows(IllegalStateException.class, decision::primaryTarget);
    }

    @Test
    void should_throwIllegalArgument_when_of_given_emptyChain() {
        // given / when / then：可执行决策必须至少一次迁移尝试
        assertThrows(IllegalArgumentException.class, () -> Decision.of(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Decision.of(null));
    }

    @Test
    void should_throwIllegalArgument_when_constructor_given_noOpReasonWithChain() {
        // given：no-op 与尝试链互斥（二者并存说明决策构造有误）
        Decision.Attempt attempt = new Decision.Attempt(Transition.FINISH_TURN, IDLE_EVENTS,
                DeploymentOutcome.TERMINATED);

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> new Decision(List.of(attempt), TerminalNoOpReason.LEASE_LOST));
    }

    @Test
    void should_copyAttemptsDefensively_when_of_given_mutableList() {
        // given：显式传入可变列表
        List<Decision.Attempt> mutable = new ArrayList<>();
        mutable.add(new Decision.Attempt(Transition.FINISH_TURN, IDLE_EVENTS, DeploymentOutcome.TERMINATED));

        // when：构造后修改原列表
        Decision decision = Decision.of(mutable);
        mutable.clear();

        // then：尝试链为不可变拷贝，构造后外部修改不影响决策
        assertEquals(1, decision.attempts().size());
        assertThrows(UnsupportedOperationException.class, () -> decision.attempts().add(null));
    }

    @Test
    void should_throwIllegalArgument_when_attempt_given_missingTransitionOrOutcome() {
        // given / when / then
        assertThrows(IllegalArgumentException.class,
                () -> new Decision.Attempt(null, IDLE_EVENTS, DeploymentOutcome.TERMINATED));
        assertThrows(IllegalArgumentException.class,
                () -> new Decision.Attempt(Transition.FINISH_TURN, IDLE_EVENTS, null));
    }

    @Test
    void should_throwIllegalArgument_when_attempt_given_missingEvents() {
        // given / when / then：迁移必须声明终态事件集（否则状态已迁而账本无痕）
        assertThrows(IllegalArgumentException.class,
                () -> new Decision.Attempt(Transition.FINISH_TURN, List.of(), DeploymentOutcome.TERMINATED));
        assertThrows(IllegalArgumentException.class,
                () -> new Decision.Attempt(Transition.FINISH_TURN, null, DeploymentOutcome.TERMINATED));
    }

    @Test
    void should_throwIllegalArgument_when_attempt_given_eventsNotEndedWithTargetStatusEvent() {
        // given：只落线程状态镜像（未以会话状态事件收尾）、或状态事件指向别的状态
        List<TerminalEventSpec> noSessionStatusEvent = List.of(
                TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED));
        List<TerminalEventSpec> wrongTarget = List.of(
                TerminalEventSpec.statusEvent(AgentSessionStatus.TERMINATED, TerminalStopReason.MAX_ITERATIONS));

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> new Decision.Attempt(Transition.FINISH_TURN, noSessionStatusEvent,
                        DeploymentOutcome.TERMINATED));
        assertThrows(IllegalArgumentException.class,
                () -> new Decision.Attempt(Transition.FINISH_TURN, wrongTarget, DeploymentOutcome.TERMINATED));
    }

    @Test
    void should_throwIllegalArgument_when_attempt_given_statusEventInconsistentWithTarget() {
        // given：链内混入指向另一状态的状态事件（先 terminated 后 idle）
        List<TerminalEventSpec> mixed = List.of(
                TerminalEventSpec.statusEvent(AgentSessionStatus.TERMINATED, TerminalStopReason.MAX_ITERATIONS),
                TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED));

        // when / then：末条虽与目标一致，但前一条状态事件已错位
        assertThrows(IllegalArgumentException.class,
                () -> new Decision.Attempt(Transition.FINISH_TURN, mixed, DeploymentOutcome.TERMINATED));
    }

    @Test
    void should_exposeImmutableEvents_when_attempt_given_constructedChain() {
        // given
        List<TerminalEventSpec> mutable = new ArrayList<>(IDLE_EVENTS);
        Decision.Attempt attempt = new Decision.Attempt(Transition.FINISH_TURN, mutable,
                DeploymentOutcome.TERMINATED);

        // when
        mutable.clear();

        // then：事件集为不可变拷贝，落库顺序即声明顺序（线程先、会话后）
        assertEquals(2, attempt.events().size());
        assertEquals(TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE,
                TerminalStopReason.INTERRUPTED), attempt.events().get(0));
        assertEquals(TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE,
                TerminalStopReason.INTERRUPTED), attempt.events().get(1));
        assertFalse(attempt.events().isEmpty());
    }
}