package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 切片内容形态枚举。
 */
public enum ChunkContentType {

    /** 纯文本 */
    TEXT,

    /** 图片 */
    IMAGE,

    /** 表格 */
    TABLE,

    /** 公式 */
    EQUATION,

    /** 通用（未分类） */
    GENERIC
}
