package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.TerminalEventSpec;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.DeploymentOutcome;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalNoOpReason;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TerminalStopReason;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnTerminalKind;

import java.util.List;
import java.util.Objects;

/**
 * 轮次终态决策纯函数（决策表的唯一事实源，无 IO、无状态）。
 * <p>输入 = <b>轮次结果</b>（{@link TurnResult}）× <b>终态事务读到的 DB 当前态</b>；
 * 输出 = <b>迁移尝试链</b>（目标态 + 终态事件集 + 调度终局语义）或 <b>no-op 判定</b>。
 * 应用层（{@code execution.TurnFinalizer} 的终态唯一出口）只负责
 * 「查输入 → 调本函数 → 按输出落库广播」，不再内联推导任何交错分支。</p>
 *
 * <table>
 *   <caption>决策表（与公开契约收场事件集逐行对照）</caption>
 *   <tr><th>轮次结果</th><th>DB 当前态</th><th>输出</th><th>落库终态事件</th><th>调度回写</th></tr>
 *   <tr><td>正常结束</td><td>running</td><td>{@code FINISH_TURN}</td>
 *       <td>{@code thread_status_idle} + {@code status_idle}（{@code end_turn}）</td><td>succeeded</td></tr>
 *   <tr><td>迭代上限</td><td>running(相位 running)</td><td>首选 {@code TERMINATE}</td>
 *       <td>{@code thread_status_terminated} + {@code status_terminated}（{@code max_iterations}）</td>
 *       <td>failed</td></tr>
 *   <tr><td>迭代上限</td><td>cancelling（取消抢跑）</td><td>首选未命中 ⇒ 回退 {@code FINISH_TURN}</td>
 *       <td>{@code thread_status_idle} + {@code status_idle}（{@code interrupted}）</td><td>terminated</td></tr>
 *   <tr><td>被中断</td><td>running / cancelling</td><td>{@code FINISH_TURN}</td>
 *       <td>{@code thread_status_idle} + {@code status_idle}（{@code interrupted}）</td><td>terminated</td></tr>
 *   <tr><td>执行出错</td><td>running</td><td>{@code FINISH_TURN}</td>
 *       <td>{@code session.error} + {@code thread_status_idle} + {@code status_idle}（{@code error}）</td>
 *       <td>failed</td></tr>
 *   <tr><td>租约丢失</td><td>任意</td><td>no-op（{@link TerminalNoOpReason#LEASE_LOST}）</td>
 *       <td>无</td><td>无</td></tr>
 *   <tr><td>任意</td><td>terminated（显式指令胜出）</td><td>no-op
 *       （{@link TerminalNoOpReason#EXPLICIT_TERMINAL_WINS}）</td><td>无</td><td>无</td></tr>
 *   <tr><td>HITL 挂起</td><td>相位 awaiting_confirmation</td><td>no-op（不收尾，
 *       {@link TerminalNoOpReason#HITL_SUSPENDED}）</td>
 *       <td>挂起全程零状态事件（等待事实由账本既有 tool_use 明细承载）</td>
 *       <td>轮未结束不回写</td></tr>
 * </table>
 *
 * <p><b>与 CAS 语义的等价性</b>：</p>
 * <ul>
 *   <li>迭代上限行即 {@code TERMINATE}（相位前置仅 {@code running}）CAS 未命中时同链回退
 *       {@link Transition#FINISH_TURN}（相位前置为空集，cancelling 亦命中），
 *       保证「取消优先于迭代上限」且 cancelling 不为静默死路；</li>
 *   <li>{@code terminated} 既不在 {@code FINISH_TURN} 也不在 {@code TERMINATE} 的对外状态前置集合内
 *       （见 {@link SessionStateMachine}），显式终态 CSS 必然 0 行、真 no-op；</li>
 *   <li>DB 当前态读不到（会话已删 / 存量非枚举值 ⇒ {@code dbStatus} 为 null）时<b>不做</b>前置判定，
 *       完全交由 CAS 仲裁。</li>
 * </ul>
 */
public final class TurnFinalizationPolicy {

    /** 执行出错终态的错误码（对外 {@code error.error_code}）。 */
    public static final String ERROR_CODE_RUN = "DEEP_AGENT_RUN_ERROR";

    private TurnFinalizationPolicy() {
    }

    /**
     * 决策：本轮终态该怎么收场。
     *
     * @param result   轮次结果（执行侧分类得出，不可为 null）
     * @param dbStatus 终态事务读到的会话当前状态（读不到传 null：跳过显式终态前置判定，交由 CAS 仲裁）
     * @return 迁移尝试链或 no-op 判定
     */
    public static Decision decide(TurnResult result, AgentSessionStatus dbStatus) {
        Objects.requireNonNull(result, "轮次结果不能为空");
        TurnTerminalKind kind = result.kind();
        // 第 6 行：租约丢失（应用层已在本函数之前短路，此处为决策表自身的显式收口，杜绝旁路误写终态）
        if (kind == TurnTerminalKind.LEASE_LOST) {
            return Decision.noOp(TerminalNoOpReason.LEASE_LOST);
        }
        // 第 8 行：HITL 挂起驻留——等待事实已随账本 tool_use 明细承载，收尾路径不参与
        if (kind == TurnTerminalKind.HITL_SUSPENDED) {
            return Decision.noOp(TerminalNoOpReason.HITL_SUSPENDED);
        }
        // 第 7 行：显式指令胜出（terminated 为不可复活终态，轮次收尾让位；
        // 归档是独立正交维度，不再参与状态判定）
        if (dbStatus == AgentSessionStatus.TERMINATED) {
            return Decision.noOp(TerminalNoOpReason.EXPLICIT_TERMINAL_WINS);
        }
        return switch (kind) {
            // 第 1 行：正常结束 → idle(end_turn)
            case COMPLETED -> Decision.of(List.of(
                    new Decision.Attempt(Transition.FINISH_TURN,
                            idleClosingEvents(TerminalStopReason.STOP),
                            DeploymentOutcome.SUCCEEDED)));
            // 第 2 / 3 行：迭代上限 → 首选 terminated(max_iterations)；取消抢跑时同链回退 idle(interrupted)
            case MAX_ITERATIONS -> Decision.of(List.of(
                    new Decision.Attempt(Transition.TERMINATE,
                            terminatedClosingEvents(),
                            DeploymentOutcome.FAILED),
                    new Decision.Attempt(Transition.FINISH_TURN,
                            idleClosingEvents(TerminalStopReason.INTERRUPTED),
                            DeploymentOutcome.TERMINATED)));
            // 第 4 行：被中断 → idle(interrupted)，零中段事件
            case INTERRUPTED -> Decision.of(List.of(
                    new Decision.Attempt(Transition.FINISH_TURN,
                            idleClosingEvents(TerminalStopReason.INTERRUPTED),
                            DeploymentOutcome.TERMINATED)));
            // 第 5 行：执行出错 → error 事件 + 收场二事件 idle(error)
            case EXECUTION_ERROR -> Decision.of(List.of(
                    new Decision.Attempt(Transition.FINISH_TURN,
                            List.of(TerminalEventSpec.errorEvent(ERROR_CODE_RUN, result.errorMessage()),
                                    TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE,
                                            TerminalStopReason.ERROR),
                                    TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE,
                                            TerminalStopReason.ERROR)),
                            DeploymentOutcome.FAILED)));
            case HITL_SUSPENDED, LEASE_LOST ->
                    throw new IllegalStateException("分支已在函数首部短路，不可达: " + kind);
        };
    }

    /**
     * 收场二事件（线程先、会话后）：主线程状态镜像 + 会话状态，共享同一 {@code stop_reason}。
     *
     * @param reason 终态原因（正常结束 / 中断）
     */
    private static List<TerminalEventSpec> idleClosingEvents(TerminalStopReason reason) {
        return List.of(
                TerminalEventSpec.threadStatusEvent(AgentSessionStatus.IDLE, reason),
                TerminalEventSpec.statusEvent(AgentSessionStatus.IDLE, reason));
    }

    /** 终止收场二事件（线程先、会话后），{@code stop_reason.type=max_iterations}。 */
    private static List<TerminalEventSpec> terminatedClosingEvents() {
        return List.of(
                TerminalEventSpec.threadStatusEvent(AgentSessionStatus.TERMINATED,
                        TerminalStopReason.MAX_ITERATIONS),
                TerminalEventSpec.statusEvent(AgentSessionStatus.TERMINATED,
                        TerminalStopReason.MAX_ITERATIONS));
    }
}