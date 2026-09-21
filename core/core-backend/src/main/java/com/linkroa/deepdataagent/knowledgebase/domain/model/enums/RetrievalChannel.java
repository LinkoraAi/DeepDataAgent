package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 检索通道枚举。
 */
public enum RetrievalChannel {

    /** 向量语义检索 */
    VECTOR,

    /** BM25 全文检索 */
    BM25,

    /** 知识图谱检索 */
    GRAPH
}
