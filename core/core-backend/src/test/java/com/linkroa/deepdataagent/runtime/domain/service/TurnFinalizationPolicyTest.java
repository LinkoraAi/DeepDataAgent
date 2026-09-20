package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.TerminalEventSpec;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.DeploymentOutcome;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalEventKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalNoOpReason;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalStopReason;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnTerminalKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnFinalizationPolicy} 终态决策表逐行单测。
 * <p>逐行独立断言，期望值按决策表<b>独立重述</b>、不从实现反推：每行锁定
 * 「迁移尝试链 + 终态事件集 + 调度回写语义」。收场事件集按「线程先、会话后」镜像，
 * 两条状态事件共享同一 {@code stop_reason}。</p>
 * <p>另锁定决策侧不变量：取消 × 迭代上限同发只收敛 idle；terminated 显式指令胜出为 no-op；
 * 事件集内状态事件永远等于迁移目标（事件表与实际状态不可能错位）。</p>
 */
class TurnFinalizationPolicyTest {

    private static final String ERROR_MESSAGE = "模型调用超时";

    // ==================== 第 1 行：正常结束 → idle(end_turn) ====================

    @Test
    void should_transitionToIdleWithEndTurn_when_decide_given_completedTurnOnRunning() {
        // given：本轮正常收流，终态事务读到 running
        TurnResult result = TurnResult.completed();

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, AgentSessionStatus.RUNNING);

        // then：单次尝试 FINISH_TURN，收场二事件 idle（stop_reason=end_turn），调度回写 succeeded
        Decision.Attempt attempt = singleAttempt(decision);
        assertEquals(Transition.FINISH_TURN, attempt.transition());
        assertEquals(AgentSessionStatus.IDLE, decision.primaryTarget());
        assertEquals(List.of(TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE,
                        TerminalStopReason.STOP),
                TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, TerminalStopReason.STOP)),
                attempt.events());
        assertEquals(DeploymentOutcome.SUCCEEDED, attempt.outcome());
    }

    // ==================== 第 2 行：迭代上限 → 首选 terminated(max_iterations) ====================

    @Test
    void should_preferTerminated_when_decide_given_maxIterationsTurnOnRunning() {
        // given：达到迭代上限，且取消未抢跑（DB 相位仍为 running）
        TurnResult result = TurnResult.maxIterations();

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, AgentSessionStatus.RUNNING);

        // then：首选尝试为 TERMINATE（收场二事件 terminated、回写 failed）
        Decision.Attempt preferred = decision.attempts().get(0);
        assertEquals(Transition.TERMINATE, preferred.transition());
        assertEquals(List.of(TerminalEventSpec.threadStatusEvent(AgentSessionStatus.TERMINATED,
                        TerminalStopReason.MAX_ITERATIONS),
                TerminalEventSpec.statusEvent(AgentSessionStatus.TERMINATED,
                        TerminalStopReason.MAX_ITERATIONS)), preferred.events());
        assertEquals(DeploymentOutcome.FAILED, preferred.outcome());
        // CAS 仲裁：running 相位在 TERMINATE 前置集合内 ⇒ 首选命中，不会误落 idle
        assertEquals(AgentSessionStatus.TERMINATED,
                adoptedTargetOf(decision, AgentSessionStatus.RUNNING, TurnPhase.RUNNING));
    }

    // ==================== 第 3 行：迭代上限 × 取消抢跑 → 回退 idle(interrupted) ====================

    @Test
    void should_declareFallbackAttemptInSameChain_when_decide_given_maxIterationsTurn() {
        // given
        TurnResult result = TurnResult.maxIterations();

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, AgentSessionStatus.RUNNING);

        // then：同链声明回退分支（FINISH_TURN + 中断收场二事件 + 回写 terminated），
        // 消除「TERMINATE 未命中再落 idle」的内联交错推导
        assertEquals(2, decision.attempts().size());
        Decision.Attempt fallback = decision.attempts().get(1);
        assertEquals(Transition.FINISH_TURN, fallback.transition());
        assertEquals(List.of(TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE,
                        TerminalStopReason.INTERRUPTED),
                TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED)),
                fallback.events());
        assertEquals(DeploymentOutcome.TERMINATED, fallback.outcome());
    }

    @ParameterizedTest
    @CsvSource({"RUNNING, RUNNING, TERMINATED", "RUNNING, CANCELLING, IDLE"})
    void should_adoptTargetByCasArbitration_when_decide_given_maxIterationsRacingWithCancel(
            AgentSessionStatus dbStatus, TurnPhase dbPhase, AgentSessionStatus expectedAdopted) {
        // given：取消与迭代上限同发——决策表只声明尝试链，真仲裁者仍是 CAS 双列前置集合
        Decision decision = TurnFinalizationPolicy.decide(TurnResult.maxIterations(), dbStatus);

        // when：按迁移表前置集合模拟 CAS 命中（与仓储 transition 的行数判定同构）
        AgentSessionStatus adopted = adoptedTargetOf(decision, dbStatus, dbPhase);

        // then：cancelling 相位下 TERMINATE（相位前置仅 running）必然 0 行 ⇒ 只收敛 idle；
        // running 相位下首选命中 terminated——两分支均不存在静默死路
        assertEquals(expectedAdopted, adopted);
    }

    // ==================== 第 4 行：被中断 → idle(interrupted) ====================

    @ParameterizedTest
    @EnumSource(value = TurnPhase.class, names = {"RUNNING", "CANCELLING"})
    void should_convergeToIdleWithInterruptedEvents_when_decide_given_interruptedTurn(TurnPhase dbPhase) {
        // given：进程内中断标志或持久 cancelling 相位痕迹命中
        TurnResult result = TurnResult.interrupted();

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, AgentSessionStatus.RUNNING);

        // then：单次尝试 FINISH_TURN（相位前置为空集，running / cancelling 两相位均可收敛），
        // 先线程镜像后会话状态，共享 interrupted 原因
        Decision.Attempt attempt = singleAttempt(decision);
        assertEquals(Transition.FINISH_TURN, attempt.transition());
        assertEquals(List.of(TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE,
                        TerminalStopReason.INTERRUPTED),
                TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, TerminalStopReason.INTERRUPTED)),
                attempt.events());
        assertEquals(DeploymentOutcome.TERMINATED, attempt.outcome());
        assertEquals(AgentSessionStatus.IDLE,
                adoptedTargetOf(decision, AgentSessionStatus.RUNNING, dbPhase));
    }

    // ==================== 第 5 行：执行出错 → error 事件 + idle(error) ====================

    @Test
    void should_emitErrorEventThenIdleClosing_when_decide_given_executionError() {
        // given：流异常且无中断标志（错误信息已脱敏）
        TurnResult result = TurnResult.executionError(ERROR_MESSAGE);

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, AgentSessionStatus.RUNNING);

        // then：先 session.error（错误码 + 原样透传已脱敏信息）再收场二事件 idle(error)，回写 failed
        Decision.Attempt attempt = singleAttempt(decision);
        assertEquals(Transition.FINISH_TURN, attempt.transition());
        assertEquals(3, attempt.events().size());
        TerminalEventSpec errorEvent = attempt.events().get(0);
        assertEquals(TerminalEventKind.SESSION_ERROR, errorEvent.kind());
        assertEquals(TurnFinalizationPolicy.ERROR_CODE_RUN, errorEvent.errorCode());
        assertEquals(ERROR_MESSAGE, errorEvent.errorMessage());
        assertEquals(TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE, TerminalStopReason.ERROR),
                attempt.events().get(1));
        assertEquals(TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, TerminalStopReason.ERROR),
                attempt.events().get(2));
        assertEquals(DeploymentOutcome.FAILED, attempt.outcome());
    }

    // ==================== 第 6 行：租约丢失 → no-op ====================

    @ParameterizedTest
    @EnumSource(AgentSessionStatus.class)
    void should_noOpWithLeaseLost_when_decide_given_leaseLostRegardlessOfDbStatus(AgentSessionStatus dbStatus) {
        // given：续约失败确立执行权丧失（应用层在本函数前已短路，本行为决策表自身的显式收口）
        TurnResult result = new TurnResult(TurnTerminalKind.LEASE_LOST, null);

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, dbStatus);

        // then：不迁移、不落库、不广播、不发终局事件（fail-closed 静默中止）
        assertTrue(decision.noOp());
        assertEquals(TerminalNoOpReason.LEASE_LOST, decision.noOpReason());
        assertTrue(decision.attempts().isEmpty());
        assertThrows(IllegalStateException.class, decision::primaryTarget);
    }

    // ==================== 第 7 行：已终止 → 显式指令胜出 no-op ====================

    @Test
    void should_noOpWithExplicitTerminalWins_when_decide_given_terminatedSession() {
        // given：轮次收尾期间用户显式终止抢先落库
        TurnResult result = TurnResult.completed();

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, AgentSessionStatus.TERMINATED);

        // then：no-op——与 CAS 完全一致：terminated 既不在 idle 也不在 terminated 的前置集合内，
        // 两条窄列 UPDATE 必然 0 行（本行只是把必然 0 行显式化，不新增仲裁者）
        assertTrue(decision.noOp());
        assertEquals(TerminalNoOpReason.EXPLICIT_TERMINAL_WINS, decision.noOpReason());
        assertTrue(decision.attempts().isEmpty());
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.TERMINATED, TurnPhase.IDLE,
                Transition.FINISH_TURN));
        assertFalse(SessionStateMachine.isLegal(AgentSessionStatus.TERMINATED, TurnPhase.IDLE,
                Transition.TERMINATE));
    }

    @ParameterizedTest
    @EnumSource(value = TurnTerminalKind.class,
            names = {"COMPLETED", "MAX_ITERATIONS", "INTERRUPTED", "EXECUTION_ERROR"})
    void should_letExplicitTerminalWin_when_decide_given_executableKindOnTerminatedSession(TurnTerminalKind kind) {
        // given：可执行轮次结果与显式终态同时出现
        TurnResult result = resultOf(kind);

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, AgentSessionStatus.TERMINATED);

        // then：一律让位于显式指令
        assertEquals(TerminalNoOpReason.EXPLICIT_TERMINAL_WINS, decision.noOpReason());
        assertTrue(decision.attempts().isEmpty());
    }

    // ==================== 第 8 行：HITL 挂起 → 不收尾 ====================

    @Test
    void should_noOpWithHitlSuspended_when_decide_given_suspendedTurn() {
        // given：物理轮结束但等待事实已随挂起事务落事件表
        TurnResult result = TurnResult.hitlSuspended();

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, AgentSessionStatus.RUNNING);

        // then：驻留不动状态（轮未结束不回写调度终局）
        assertTrue(decision.noOp());
        assertEquals(TerminalNoOpReason.HITL_SUSPENDED, decision.noOpReason());
        assertTrue(decision.attempts().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(AgentSessionStatus.class)
    void should_keepHitlNoOp_when_decide_given_suspendedRegardlessOfDbStatus(AgentSessionStatus dbStatus) {
        // given / when：挂起判定先于显式终态判定
        Decision decision = TurnFinalizationPolicy.decide(TurnResult.hitlSuspended(), dbStatus);

        // then
        assertEquals(TerminalNoOpReason.HITL_SUSPENDED, decision.noOpReason());
    }

    // ==================== 函数全性与防御分支 ====================

    @Test
    void should_returnDeterministicDecision_when_decide_given_allKindAndStatusCombinations() {
        // given / when / then：6 种轮次结果 × 5 态（含读不到态）全组合均有确定输出且不抛异常
        for (TurnTerminalKind kind : TurnTerminalKind.values()) {
            for (AgentSessionStatus dbStatus : allStatusesIncludingUnknown()) {
                TurnResult result = resultOf(kind);

                Decision decision = TurnFinalizationPolicy.decide(result, dbStatus);

                assertNotNull(decision);
                assertEquals(decision.noOp(), decision.attempts().isEmpty(),
                        kind + " × " + dbStatus + " 的 no-op 标记与尝试链不一致");
                if (!decision.noOp()) {
                    assertChainInternallyConsistent(decision);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = TurnTerminalKind.class,
            names = {"COMPLETED", "MAX_ITERATIONS", "INTERRUPTED", "EXECUTION_ERROR"})
    void should_delegateToCasWithoutPrejudgement_when_decide_given_dbStatusUnavailable(TurnTerminalKind kind) {
        // given：终态事务读不到当前态（会话行已删 / 存量取值非四态 ⇒ null）
        TurnResult result = resultOf(kind);

        // when
        Decision decision = TurnFinalizationPolicy.decide(result, null);

        // then：不做显式终态前置判定，直接给出尝试链，终态资格完全交由 CAS 仲裁
        assertFalse(decision.noOp());
        assertFalse(decision.attempts().isEmpty());
    }

    @Test
    void should_throwNullPointer_when_decide_given_nullTurnResult() {
        // given / when / then
        assertThrows(NullPointerException.class,
                () -> TurnFinalizationPolicy.decide(null, AgentSessionStatus.RUNNING));
    }

    // ==================== 辅助 ====================

    /** 按种类构造轮次结果（执行出错需携带已脱敏错误信息）。 */
    private static TurnResult resultOf(TurnTerminalKind kind) {
        return kind == TurnTerminalKind.EXECUTION_ERROR
                ? TurnResult.executionError(ERROR_MESSAGE)
                : new TurnResult(kind, null);
    }

    /** 单尝试断言：非 no-op 且恰好一次迁移。 */
    private static Decision.Attempt singleAttempt(Decision decision) {
        assertFalse(decision.noOp(), "期望可执行决策，实际为 no-op");
        assertEquals(1, decision.attempts().size());
        return decision.attempts().get(0);
    }

    /**
     * 按迁移表前置集合模拟 CAS 仲裁，得出实际被采纳的尝试目标态。
     * <p>与仓储 {@code transition} 的行数判定同构（双列守卫同时满足才命中）。</p>
     */
    private static AgentSessionStatus adoptedTargetOf(Decision decision, AgentSessionStatus dbStatus,
                                                      TurnPhase dbPhase) {
        return decision.attempts().stream()
                .filter(attempt -> SessionStateMachine.isLegal(dbStatus, dbPhase, attempt.transition()))
                .findFirst()
                .map(attempt -> attempt.transition().to())
                .orElseThrow(() -> new AssertionError("尝试链在该当前双列取值下全部未命中"));
    }

    /** 尝试链内部一致性：事件集非空、以会话状态事件收尾、状态事件一律指向本迁移目标。 */
    private static void assertChainInternallyConsistent(Decision decision) {
        for (Decision.Attempt attempt : decision.attempts()) {
            assertNotNull(attempt.outcome());
            assertFalse(attempt.events().isEmpty());
            TerminalEventSpec last = attempt.events().get(attempt.events().size() - 1);
            assertEquals(TerminalEventKind.SESSION_STATUS, last.kind());
            assertEquals(attempt.transition().to(), last.status());
            attempt.events().stream()
                    .filter(event -> event.kind() == TerminalEventKind.SESSION_STATUS)
                    .forEach(event -> assertEquals(attempt.transition().to(), event.status()));
        }
    }

    /** 决策表输入域：四个对外状态 + 「读不到当前态」（null）。 */
    private static List<AgentSessionStatus> allStatusesIncludingUnknown() {
        List<AgentSessionStatus> statuses = new ArrayList<>(Arrays.asList(AgentSessionStatus.values()));
        statuses.add(null);
        return statuses;
    }
}