package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 轮次结果种类（终态决策纯函数 {@code TurnFinalizationPolicy} 的输入侧词汇）。
 * <p>由执行侧在收流 / 异常 / 兜底路径上归类得出（对应计划 4.4 决策表「轮次结果」列），
 * 领域内只声明「本轮以什么语义收场」，不关心怎么落库。</p>
 */
public enum TurnTerminalKind {

    /** 正常结束（AGENT_END / onComplete 兜底且未触上限）：回 idle，{@code stop_reason=stop} */
    COMPLETED,

    /** 达到迭代上限：进 terminated，{@code stop_reason=max_iterations}；取消抢跑时回退 idle */
    MAX_ITERATIONS,

    /** 被中断（进程内中断标志 / 跨进程 canceling 持久痕迹 / 流异常带中断标志）：回 idle，中断语义 */
    INTERRUPTED,

    /** 执行出错（流异常无中断标志 / 构建注册失败）：回 idle，伴随 {@code session.error} */
    EXECUTION_ERROR,

    /** HITL 挂起（waiting_confirmation 驻留）：物理轮结束但<b>不收尾</b>——等待事实存事件账本 */
    HITL_SUSPENDED,

    /**
     * 租约丢失（续约失败确立执行权丧失）：fail-closed 静默中止——不迁移状态、不落库、不广播、
     * 不释放租约（终态权归复位路径或新持有者）。应用层<b>应在进入策略前短路</b>，
     * 本行为决策表对该分支的显式收口（防御性兜底，见 design D3）。
     */
    LEASE_LOST
}
