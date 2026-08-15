package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 执行轮次状态（对应 execution_round.status）。
 */
public enum RoundStatus {
    /** 执行中 */
    RUNNING,
    /** 正常完成 */
    COMPLETED,
    /** 执行失败（未捕获异常） */
    FAILED,
    /** 中断（客户端断开 / 进程重启恢复） */
    INTERRUPTED
}