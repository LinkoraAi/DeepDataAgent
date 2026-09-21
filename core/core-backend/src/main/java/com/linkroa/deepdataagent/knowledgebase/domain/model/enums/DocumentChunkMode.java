package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 文档引擎分块模式枚举。
 */
public enum DocumentChunkMode {

    /** 通用分块 */
    GENERAL,

    /** QA 问答对分块 */
    QA,

    /** 书籍分块 */
    BOOK,

    /** 法律法规分块 */
    LAWS,

    /** 表格分块 */
    TABLE,

    /** 演示文稿分块 */
    PRESENTATION,

    /** 整文档单块 */
    ONE
}
