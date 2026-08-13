package com.linkroa.deepdataagent.agent.domain.exception;

import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;

/**
 * 数据分析被用户主动取消的领域异常。
 * <p>前端终止报告分析时，LLM 调用线程被中断（{@link InterruptedException}），
 * 该异常由基础设施层（如 {@code LLMInvoker}）抛出，用于表达取消语义而非真实模型故障。</p>
 */
public class AnalysisCancelledException extends DeepDataAgentException {

    public AnalysisCancelledException(String message) {
        super(message);
    }

    /**
     * 便捷判断：异常（或 cause 链）是否表示分析被取消。
     *
     * @param e 异常
     * @return true 表示为取消异常
     */
    public static boolean isCancelled(Throwable e) {
        return e instanceof AnalysisCancelledException
                || e instanceof InterruptedException
                || isCancelledInCause(e);
    }

    private static boolean isCancelledInCause(Throwable e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof AnalysisCancelledException || cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}