package com.linkroa.deepdataagent.runtime.domain.model.enums;

/**
 * Agent 会话状态（对应 agent_session.status）。
 */
public enum AgentSessionStatus {
    /** 空闲，可接收新消息 */
    IDLE,
    /** 执行中（单飞，同一会话同一时刻仅允许一个执行流） */
    RUNNING,
    /** 已终止（不再接收消息，但历史可查询/回放） */
    TERMINATED
}