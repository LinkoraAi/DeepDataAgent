package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnTerminalKind;
import org.apache.commons.lang3.StringUtils;

/**
 * 轮次结果（终态决策纯函数的输入载体）：本轮以什么语义收场 + 出错时的已脱敏错误信息。
 * <p>静态分类方法收编现状散在执行回调里的分支判定（分支求值顺序与现状完全一致，
 * 应用层负责按现状顺序惰性求值，不得提前触发额外 DB 读）：
 * {@link #classifyStreamClose} 对应 SDK 正常收流（onComplete）的四分支裁决——
 * 在途取消 &gt; HITL 挂起驻留 &gt; 迭代上限 &gt; 正常结束。流异常（onError）与兜底失败路径
 * 依现状维持<b>进程内中断标志</b>单源判定，由应用层选择 {@link #interrupted()} /
 * {@link #executionError(String)} 构造。</p>
 *
 * @param kind         轮次结果种类
 * @param errorMessage 已脱敏错误信息（仅 {@link TurnTerminalKind#EXECUTION_ERROR} 携带）
 */
public record TurnResult(TurnTerminalKind kind, String errorMessage) {

    public TurnResult {
        if (kind == null) {
            throw new IllegalArgumentException("轮次结果种类不能为空");
        }
        if (kind == TurnTerminalKind.EXECUTION_ERROR && StringUtils.isBlank(errorMessage)) {
            throw new IllegalArgumentException("执行出错终态必须携带错误信息");
        }
    }

    /** 正常结束（未触迭代上限）。 */
    public static TurnResult completed() {
        return new TurnResult(TurnTerminalKind.COMPLETED, null);
    }

    /** 达到迭代上限。 */
    public static TurnResult maxIterations() {
        return new TurnResult(TurnTerminalKind.MAX_ITERATIONS, null);
    }

    /** 被中断（进程内标志或持久 canceling 痕迹命中）。 */
    public static TurnResult interrupted() {
        return new TurnResult(TurnTerminalKind.INTERRUPTED, null);
    }

    /** HITL 挂起驻留（不收尾）。 */
    public static TurnResult hitlSuspended() {
        return new TurnResult(TurnTerminalKind.HITL_SUSPENDED, null);
    }

    /**
     * 执行出错。
     *
     * @param sanitizedMessage 已脱敏且非空的错误信息
     */
    public static TurnResult executionError(String sanitizedMessage) {
        return new TurnResult(TurnTerminalKind.EXECUTION_ERROR, sanitizedMessage);
    }

    /**
     * 正常收流终态分类（取消优先于迭代上限：竞态时按中断语义收敛回 idle，不落 terminated）。
     *
     * @param cancelRequested     在途取消两源谓词结果（进程内标志 || 持久 canceling 痕迹）
     * @param confirmationPending HITL 挂起守卫（等待事实已存账本）
     * @param exceededMaxIters    本轮是否触达迭代上限
     */
    public static TurnResult classifyStreamClose(boolean cancelRequested, boolean confirmationPending,
                                                 boolean exceededMaxIters) {
        if (cancelRequested) {
            return interrupted();
        }
        if (confirmationPending) {
            return hitlSuspended();
        }
        return exceededMaxIters ? maxIterations() : completed();
    }
}
