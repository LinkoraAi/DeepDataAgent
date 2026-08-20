package com.linkroa.deepdataagent.runtime.domain.model.enums;

import java.util.Set;

/**
 * 会话执行状态机（状态层核心状态机）—— 纯内存态，一轮执行的内部流转。
 * <p>本枚举<b>不持久化、不直接推送客户端</b>，只管「一轮执行」的流转与结束原因，
 * 与会话级持久化/对外状态 {@link AgentSessionStatus} 正交：</p>
 * <pre>
 *   IDLE → RUNNING → DONE        → IDLE
 *                   → INTERRUPTED → IDLE
 *                   → ERROR       → IDLE
 * </pre>
 * <p>{@code DONE/INTERRUPTED/ERROR} 为内存瞬态终态（一轮结束即回 {@code IDLE}）；
 * 会话级永久终态 {@code TERMINATED} 不存在于本状态机，由
 * {@link AgentSessionStatus#TERMINATED} 承载。</p>
 */
public enum SessionState {

    /** 会话空闲，等待用户输入 */
    IDLE,

    /** Agent 正在执行 */
    RUNNING,

    /** 一轮正常结束 */
    DONE,

    /** 用户主动中断 */
    INTERRUPTED,

    /** 执行异常 */
    ERROR;

    private static final Set<SessionState> IDLE_TRANSITIONS = Set.of(RUNNING);
    private static final Set<SessionState> RUNNING_TRANSITIONS = Set.of(DONE, INTERRUPTED, ERROR);
    private static final Set<SessionState> DONE_TRANSITIONS = Set.of(IDLE);
    private static final Set<SessionState> INTERRUPTED_TRANSITIONS = Set.of(IDLE);
    private static final Set<SessionState> ERROR_TRANSITIONS = Set.of(IDLE);

    /**
     * 校验状态转换是否合法。
     *
     * @param target 目标状态
     * @return 合法返回 true
     */
    public boolean canTransitionTo(SessionState target) {
        return switch (this) {
            case IDLE -> IDLE_TRANSITIONS.contains(target);
            case RUNNING -> RUNNING_TRANSITIONS.contains(target);
            case DONE -> DONE_TRANSITIONS.contains(target);
            case INTERRUPTED -> INTERRUPTED_TRANSITIONS.contains(target);
            case ERROR -> ERROR_TRANSITIONS.contains(target);
        };
    }

    /**
     * 执行状态转换校验，不合法时抛 {@link IllegalStateException}。
     *
     * @param target 目标状态
     */
    public void validateTransition(SessionState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("非法会话状态转换: " + this + " → " + target);
        }
    }

    /**
     * 将内存终态投影为 {@code execution_round.status}（落库投影，非第三源）。
     * <p>仅终态（{@code DONE/INTERRUPTED/ERROR}）可落库；非终态（{@code IDLE/RUNNING}）
     * 调用属编程错误，抛 {@link IllegalStateException}。</p>
     *
     * @return 落库轮次状态（DONE→COMPLETED、INTERRUPTED/ERROR→FAILED）
     */
    public RoundStatus toRoundStatus() {
        return switch (this) {
            case DONE -> RoundStatus.COMPLETED;
            case INTERRUPTED, ERROR -> RoundStatus.FAILED;
            case IDLE, RUNNING -> throw new IllegalStateException("非终态无落库投影: " + this);
        };
    }
}