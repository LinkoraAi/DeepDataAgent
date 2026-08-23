package com.linkroa.deepdataagent.memory.domain.model.enums;

/**
 * 记忆类型枚举
 */
public enum MemoryType {

    /** 短期记忆（会话内隔离） */
    SHORT_TERM,

    /** 长期记忆（跨会话共享检索） */
    LONG_TERM
}