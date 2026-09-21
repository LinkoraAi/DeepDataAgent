package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 去重判定依据枚举（两轴模型第一轴：拿什么判重）。
 */
public enum DedupMatchRule {

    /** 不检测（关闭去重） */
    NONE,

    /** 按文件名判重 */
    BY_NAME,

    /** 按内容哈希判重 */
    BY_CONTENT,

    /** 文件名或内容哈希命中即视为重复 */
    BY_NAME_OR_CONTENT
}
