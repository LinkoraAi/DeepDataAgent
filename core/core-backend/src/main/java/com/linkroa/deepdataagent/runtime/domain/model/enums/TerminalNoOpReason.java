package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * 终态决策 no-op 原因（决策表第 6 / 7 / 8 行的分类依据）。
 * <p>no-op 一律表示「本轮不写任何终态事实」：不迁移会话状态、不落终态事件、不广播、不发终局事件。</p>
 */
public enum TerminalNoOpReason {

    /** 租约丢失：执行权已丧失，终态权归复位路径或新持有者（fail-closed） */
    LEASE_LOST,

    /** HITL 挂起：等待驻留不是运行终局，轮物理结束但会话态由挂起事务写入，收尾路径不参与 */
    HITL_SUSPENDED,

    /** 显式指令胜出：会话已被并发归档 / 终止（终态不可复活），轮次收尾让位于显式终态 */
    EXPLICIT_TERMINAL_WINS
}
