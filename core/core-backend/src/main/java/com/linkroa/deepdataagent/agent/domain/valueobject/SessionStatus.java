package com.linkroa.deepdataagent.agent.domain.valueobject;

/**
 * 会话生命周期状态
 * <p>ACTIVE 表示会话活跃、可发起新对话；DELETED 表示会话已被删除（软删除）。
 * 运行中（running）是派生态，由 {@code RunningAnalysisRegistry} 内存维护，不在此持久化枚举中。</p>
 */
public enum SessionStatus {
    /** 活跃：可发起新对话 */
    ACTIVE,
    /** 已删除：软删除终态，不可再发起对话 */
    DELETED
}
