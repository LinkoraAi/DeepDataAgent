package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 检索策略类型枚举。
 */
public enum RetrievalStrategyType {

    /** 朴素检索（向量 or BM25 单通道） */
    NAIVE,

    /** 混合检索（多通道融合 + 图谱） */
    MIX
}
