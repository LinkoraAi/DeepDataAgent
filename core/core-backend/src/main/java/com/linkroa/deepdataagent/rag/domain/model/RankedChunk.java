package com.linkroa.deepdataagent.rag.domain.model;

/**
 * 融合/精排后的全局有序 chunk 值对象。
 *
 * @param chunkId    chunk 唯一标识
 * @param score      全局分数（RRF 累积分或重排分，越高越相关）
 * @param sourceFile 来源文件名（可为 null，供引用列表兜底）
 */
public record RankedChunk(
        Long chunkId,
        double score,
        String sourceFile
) {
}