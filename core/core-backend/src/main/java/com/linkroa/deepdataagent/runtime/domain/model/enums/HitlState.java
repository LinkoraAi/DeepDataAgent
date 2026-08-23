package com.linkroa.deepdataagent.runtime.domain.model.enums;

import java.util.Set;

/**
 * 人工介入（HITL）正交子态 —— 与会话执行状态机 {@link SessionState} 正交。
 * <p>会话执行状态机只管「一轮执行」的流转（IDLE / RUNNING / DONE / INTERRUPTED / ERROR），
 * 而 HITL 是执行过程中的「暂停-确认」正交维度，不改变 {@link SessionState} 的 RUNNING 事实：</p>
 * <pre>
 *   NONE ⇄ WAITING_CONFIRM
 * </pre>
 * <ul>
 *   <li>{@code NONE}：无待确认项；</li>
 *   <li>{@code WAITING_CONFIRM}：执行遇 {@code REQUIRE_*} 挂起，等待人工确认 / 拒绝。</li>
 * </ul>
 * <p>「暂停」= {@code NONE → WAITING_CONFIRM}；「恢复 / 拒绝」= {@code WAITING_CONFIRM → NONE}，
 * 拒绝的终止语义由应用层经 {@link SessionState#RUNNING → INTERRUPTED} 的既有中断路径表达。</p>
 */
public enum HitlState {

    /** 无待确认项 */
    NONE,

    /** 等待人工确认 */
    WAITING_CONFIRM;

    private static final Set<HitlState> NONE_TRANSITIONS = Set.of(WAITING_CONFIRM);
    private static final Set<HitlState> WAITING_CONFIRM_TRANSITIONS = Set.of(NONE);

    /**
     * 校验 HITL 子态转换是否合法（暂停 / 恢复 / 拒绝均收敛为 NONE ⇄ WAITING_CONFIRM）。
     *
     * @param target 目标子态
     * @return 合法返回 true
     */
    public boolean canTransitionTo(HitlState target) {
        return switch (this) {
            case NONE -> NONE_TRANSITIONS.contains(target);
            case WAITING_CONFIRM -> WAITING_CONFIRM_TRANSITIONS.contains(target);
        };
    }

    /**
     * 执行 HITL 子态转换校验，不合法时抛 {@link IllegalStateException}。
     *
     * @param target 目标子态
     */
    public void validateTransition(HitlState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("非法 HITL 状态转换: " + this + " → " + target);
        }
    }
}