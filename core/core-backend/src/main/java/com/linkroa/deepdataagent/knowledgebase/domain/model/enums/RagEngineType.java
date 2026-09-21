package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * RAG 引擎类型枚举：区分文档引擎与媒体引擎。
 */
public enum RagEngineType {

    /** 文档引擎（PDF/DOC/XLS 等文本类） */
    DOCUMENT_ENGINE,

    /** 媒体引擎（图片/音频/视频） */
    MEDIA_ENGINE
}
