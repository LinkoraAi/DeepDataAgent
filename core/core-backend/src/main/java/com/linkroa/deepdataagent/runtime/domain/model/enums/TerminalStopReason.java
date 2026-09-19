package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 会话 / 线程状态事件的终态原因（对外 {@code stop_reason.type} 值域，计划 4.4 决策表逐行引用）。
 * <p>取值是对外契约词汇（小写下划线），此处为唯一声明点：应用层不得再各写一份字面量。
 * 枚举名与对外值不再一一同形（正常结束的枚举名 {@link #STOP} 对应公开值 {@code end_turn}），
 * 故对外值由 {@link #value()} 显式承载。</p>
 */
public enum TerminalStopReason {

    /** 一轮正常结束（对外值 {@code end_turn}） */
    STOP("end_turn"),

    /** 达到迭代上限（terminated 终态携带） */
    MAX_ITERATIONS("max_iterations"),

    /** 执行出错（idle 回落携带，错误详情见 {@code session.error}） */
    ERROR("error"),

    /** 被中断 / 取消收敛（idle 回落携带，非 cancelled 终态） */
    INTERRUPTED("interrupted");

    private final String value;

    TerminalStopReason(String value) {
        this.value = value;
    }

    /**
     * 规范对外取值（如 {@code "end_turn"}、{@code "max_iterations"}）。
     *
     * @return stop_reason.type 字符串
     */
    public String value() {
        return value;
    }
}