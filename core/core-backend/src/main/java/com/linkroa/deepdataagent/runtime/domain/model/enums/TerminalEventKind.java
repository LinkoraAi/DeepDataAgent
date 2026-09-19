package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 终态事件种类（决策表「落库终态事件」列的领域判别式）。
 * <p>领域只声明事件种类与携带的语义值，payload 装配细节留在领域事件装配工厂
 * {@code ChatEventFactory}（domain.event）内，避免领域反向依赖协议层。</p>
 * <p>收场事件集按「线程先、会话后」镜像：{@link #THREAD_STATUS} 与 {@link #SESSION_STATUS}
 * 成对出现，{@code session.interrupted} 已废止（中断仅以收场二事件表达）。</p>
 */
public enum TerminalEventKind {

    /** 主线程状态镜像事件（{@code session.thread_status_<status>}，可携带 stop_reason） */
    THREAD_STATUS,

    /** 会话状态事件（{@code session.status_<status>}，可携带 stop_reason） */
    SESSION_STATUS,

    /** 会话错误事件（{@code session.error}，携带错误码与已脱敏的错误信息） */
    SESSION_ERROR
}