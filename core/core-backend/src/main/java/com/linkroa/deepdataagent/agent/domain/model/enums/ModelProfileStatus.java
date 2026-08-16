package com.linkroa.deepdataagent.agent.domain.model.enums;

/**
 * 模型配置状态枚举
 */
public enum ModelProfileStatus {

    /** 启用（可被新 Agent 版本引用） */
    ENABLED,
    /** 禁用（不可被新引用，既有会话/已发布版本运行不受影响） */
    DISABLED
}