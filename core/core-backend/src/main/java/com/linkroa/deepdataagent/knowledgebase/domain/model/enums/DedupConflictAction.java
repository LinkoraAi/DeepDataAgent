package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 去重冲突处置动作枚举（两轴模型第二轴：命中后怎么处置）。
 */
public enum DedupConflictAction {

    /** 拒绝导入 */
    REJECT,

    /** 跳过重复文件 */
    SKIP,

    /** 覆盖已有文件 */
    OVERWRITE
}
