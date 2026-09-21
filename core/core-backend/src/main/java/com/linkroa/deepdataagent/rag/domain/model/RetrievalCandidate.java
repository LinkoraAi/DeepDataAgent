package com.linkroa.deepdataagent.rag.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalChannel;

/**
 * 单通道召回候选值对象（召回 Stage 1 产物，携带通道标记）。
 * <p>{@code score} 为通道内分数：RRF 融合时仅用排名（列表下标 + 1），
 * WEIGHTED_SUM 融合时用归一化后的分数；{@code sourceFile} 可选，供引用列表使用。</p>
 *
 * @param chunkId    chunk 唯一标识
 * @param channel    召回来源通道（VECTOR / BM25 / GRAPH）
 * @param score      通道内分数（RRF 用 rank，WEIGHTED_SUM 用归一化 score）
 * @param sourceFile 来源文件名（可为 null）
 */
public record RetrievalCandidate(
        Long chunkId,
        RetrievalChannel channel,
        double score,
        String sourceFile
) {
}