package com.linkroa.deepdataagent.knowledgebase.domain.model;

/**
 * 切片检索命中投影（VECTOR / BM25 只读检索结果承载）。
 * <p>保留命中切片主键与相关度分，供上层聚合、回原表取正文。得分属于检索侧语义，
 * 不并入 {@link Chunk} 领域模型，故以独立只读投影承载，仅限本 BC 仓储接口使用。</p>
 *
 * @param chunkId 命中切片主键
 * @param score   相关度分（VECTOR 通道为余弦相似度，BM25 通道为 ts_rank_cd 相关度）
 */
public record ChunkScoreHit(Long chunkId, double score) {
}