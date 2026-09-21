package com.linkroa.deepdataagent.knowledgebase.application.contract;

/**
 * 检索命中切片契约（跨 BC Published Language）。
 * <p>提供方为 knowledgebase BC，消费方为 rag BC：检索编排侧聚合命中切片时，
 * 先经本契约拿到命中切片主键与相关度分，再按主键集合回原表取正文。</p>
 *
 * @param chunkId 命中切片主键
 * @param score   相关度分（VECTOR 通道为余弦相似度，BM25 通道为归一化相关度）
 */
public record ChunkScoreDTO(Long chunkId, double score) {
}